package com.vknxworks.proxctrl.ui.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Task
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.tasks.ProxTask
import com.vknxworks.proxctrl.data.tasks.StatusFilter
import com.vknxworks.proxctrl.data.tasks.TaskApi
import com.vknxworks.proxctrl.data.tasks.TaskFilter
import com.vknxworks.proxctrl.data.tasks.TaskList
import com.vknxworks.proxctrl.ui.components.ConfirmDialog
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.EmptyState
import com.vknxworks.proxctrl.ui.components.PollWhileVisible
import com.vknxworks.proxctrl.ui.components.StaleBanner
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

private const val RUNNING_POLL_MS = 3_000L
private const val HISTORY_POLL_MS = 20_000L
private const val PAGE = 50
/** Only the first few running tasks get their latest log line read on every poll. */
private const val LIVE_LINES = 5

/**
 * Running tasks (live, with Stop) and the filterable history, for the Tasks tab and for one guest's
 * task history ([fixedVmid]). [header] is the tab title or the screen's top bar.
 */
@Composable
fun TaskListContent(
    fixedVmid: Int?,
    bottomPadding: Dp,
    header: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val guests by c.guests.state.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val snackbar = remember { SnackbarHostState() }

    var nodes by remember(server?.id) { mutableStateOf<List<String>?>(null) }
    var running by remember(server?.id) { mutableStateOf<List<ProxTask>>(emptyList()) }
    var lastLines by remember(server?.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var history by remember(server?.id) { mutableStateOf<List<ProxTask>?>(null) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var updatedAt by remember { mutableLongStateOf(0L) }
    var refreshing by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf<ProxTask?>(null) }

    var statusName by rememberSaveable { mutableStateOf(StatusFilter.ALL.name) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var user by rememberSaveable { mutableStateOf<String?>(null) }
    var vmidFilter by rememberSaveable { mutableStateOf<Int?>(null) }
    var limit by rememberSaveable { mutableIntStateOf(PAGE) }
    val filter = TaskFilter(
        status = StatusFilter.entries.firstOrNull { it.name == statusName } ?: StatusFilter.ALL,
        type = type,
        user = user,
        vmid = fixedVmid ?: vmidFilter,
    )

    suspend fun nodesFor(cl: PveClient): List<String> = nodes ?: TaskList.nodes(cl).also { nodes = it }

    suspend fun loadRunning(cl: PveClient): Boolean = try {
        val list = TaskList.running(cl, nodesFor(cl)).filter { fixedVmid == null || it.vmid == fixedVmid }
        running = list
        lastLines = list.take(LIVE_LINES).associate { t ->
            t.upid to runCatching { TaskApi.logTail(cl, t.upid, 1).lastOrNull().orEmpty() }.getOrDefault(lastLines[t.upid].orEmpty())
        }
        true
    } catch (e: PveError) {
        error = e
        false
    }

    suspend fun loadHistory(cl: PveClient): Boolean = try {
        history = TaskList.history(cl, nodesFor(cl), filter, limit).filter { !it.isRunning }
        error = null
        updatedAt = System.currentTimeMillis()
        true
    } catch (e: PveError) {
        error = e
        false
    }

    PollWhileVisible(server?.id to fixedVmid, RUNNING_POLL_MS) {
        val cl = client ?: return@PollWhileVisible false
        loadRunning(cl)
    }
    PollWhileVisible(listOf(server?.id, filter, limit), HISTORY_POLL_MS) {
        val cl = client ?: return@PollWhileVisible false
        loadHistory(cl)
    }

    val names = remember(guests.guests) { guests.guests.associate { it.vmid to it.name } }
    val list = history
    val typeChoices = remember(list, type) {
        (COMMON_TYPES + list.orEmpty().map { it.type } + listOfNotNull(type)).distinct()
    }
    val userChoices = remember(list, user) {
        (list.orEmpty().map { it.user } + running.map { it.user } + listOfNotNull(user)).filter { it.isNotBlank() }.distinct().sorted()
    }
    val guestChoices = if (fixedVmid != null) null else guests.guests.sortedBy { it.vmid }.map { it.vmid to it.name }
    val authId = server?.authId.orEmpty()
    fun canStop(t: ProxTask): Boolean =
        server?.readOnly == false && (t.isOwnedBy(authId) || c.sessions.canWrite("/nodes/${t.node}", TaskList.STOP_PRIV))
    val seesAll = nodes.orEmpty().all { access.permissions.has("/nodes/$it", TaskList.AUDIT_PRIV) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            header()
            val err = error
            if (access.error != null) {
                ConnectionBanner()
            } else if (err != null) {
                StaleBanner(updatedAt, err, server?.host.orEmpty(), server?.auth) {
                    scope.launch { client?.let { cl -> loadRunning(cl); loadHistory(cl) } }
                }
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    scope.launch {
                        client?.let { cl ->
                            nodes = null
                            loadRunning(cl)
                            loadHistory(cl)
                        }
                        refreshing = false
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
                    if (running.isNotEmpty()) {
                        item(key = "running-title") { SectionTitle(stringResource(R.string.tasks_section_running)) }
                        items(running, key = { "run-" + it.upid }) { t ->
                            RunningTaskCard(
                                task = t,
                                guestName = t.vmid?.let(names::get),
                                lastLine = lastLines[t.upid],
                                canStop = canStop(t),
                                onStop = { stopping = t },
                                onClick = { nav.navigate(TaskDetailRoute(t.upid)) },
                            )
                        }
                    }
                    item(key = "history-title") { SectionTitle(stringResource(R.string.tasks_section_history)) }
                    item(key = "filters") {
                        TaskFilterRow(
                            status = filter.status,
                            type = type,
                            user = user,
                            vmid = vmidFilter,
                            types = typeChoices,
                            users = userChoices,
                            guests = guestChoices,
                            onStatus = { statusName = it.name; limit = PAGE; history = null },
                            onType = { type = it; limit = PAGE; history = null },
                            onUser = { user = it; limit = PAGE; history = null },
                            onGuest = { vmidFilter = it; limit = PAGE; history = null },
                        )
                    }
                    if (!seesAll && nodes != null) {
                        item(key = "own-only") {
                            Text(
                                stringResource(R.string.tasks_own_only),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 4.dp),
                            )
                        }
                    }
                    when {
                        list == null && err == null -> item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                        list == null -> item(key = "error") {
                            Text(
                                errorMessage(context, err!!, server?.host.orEmpty(), server?.auth),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(Gutter),
                            )
                        }
                        list.isEmpty() -> item(key = "empty") {
                            EmptyState(
                                icon = Icons.Outlined.Task,
                                title = stringResource(R.string.tasks_empty),
                                body = stringResource(if (filter.isEmpty || (fixedVmid != null && filter.copy(vmid = null).isEmpty)) R.string.tasks_empty_body else R.string.tasks_empty_filtered),
                                modifier = Modifier.heightIn(min = 280.dp),
                            )
                        }
                        else -> {
                            items(list, key = { "task-" + it.upid }) { t ->
                                Column {
                                    TaskRow(t, t.vmid?.let(names::get)) { nav.navigate(TaskDetailRoute(t.upid)) }
                                    HorizontalDivider(Modifier.padding(start = 50.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                }
                            }
                            if (list.size >= limit) {
                                item(key = "more") {
                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        OutlinedButton(onClick = { limit += PAGE }) { Text(stringResource(R.string.tasks_load_more)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding + 8.dp))
    }

    stopping?.let { t ->
        ConfirmDialog(
            title = stringResource(R.string.tasks_stop_title),
            body = stringResource(R.string.tasks_stop_body, taskDescription(context, t.type, t.id, t.vmid?.let(names::get))),
            confirmLabel = stringResource(R.string.tasks_stop),
            onDismiss = { stopping = null },
            onConfirm = {
                stopping = null
                val cl = client ?: return@ConfirmDialog
                scope.launch {
                    val message = try {
                        TaskList.stop(cl, t.upid)
                        context.getString(R.string.tasks_stop_sent)
                    } catch (e: PveError) {
                        errorMessage(context, e, server?.host.orEmpty(), server?.auth)
                    }
                    loadRunning(cl)
                    snackbar.showSnackbar(message)
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Gutter + 4.dp, end = Gutter, top = 12.dp, bottom = 4.dp),
    )
}
