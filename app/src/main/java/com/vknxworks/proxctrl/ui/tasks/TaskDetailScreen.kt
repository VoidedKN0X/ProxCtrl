package com.vknxworks.proxctrl.ui.tasks

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.tasks.LogLine
import com.vknxworks.proxctrl.data.tasks.ProxTask
import com.vknxworks.proxctrl.data.tasks.TaskApi
import com.vknxworks.proxctrl.data.tasks.TaskList
import com.vknxworks.proxctrl.ui.components.ConfirmDialog
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.PollWhileVisible
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.copySensitive
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.guests.formatDateTime
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

private const val LIVE_POLL_MS = 1_500L
private const val LOG_PAGE = 1000
/** Very long logs (a backup of a big disk) stop being read here; the rest is on the server. */
private const val MAX_LINES = 50_000

/**
 * One task: what it is, who started it, when, how long it took and how it ended, and its full log.
 * While it runs, the log follows new lines (scroll up to stop following) and Stop is offered.
 */
@Composable
fun TaskDetailScreen(upid: String) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val guests by c.guests.state.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val decoded = remember(upid) { TaskList.decode(upid) }

    var running by remember { mutableStateOf<Boolean?>(null) }
    var exitStatus by remember { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf<ProxTask?>(null) }
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var truncated by remember { mutableStateOf(false) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var confirmStop by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Following: the end of the log is in view (or nothing is loaded yet).
    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
            last >= info.totalItemsCount - 2
        }
    }

    /** Reads every log line after the ones already loaded. */
    suspend fun readMore(cl: PveClient) {
        var after = lines.lastOrNull()?.n ?: 0L
        while (lines.size < MAX_LINES) {
            val page = TaskList.log(cl, upid, after, LOG_PAGE)
            if (page.isEmpty()) break
            lines = lines + page
            after = page.last().n
            if (page.size < LOG_PAGE) break
        }
        truncated = lines.size >= MAX_LINES
    }

    suspend fun refresh(cl: PveClient): Boolean = try {
        val state = TaskApi.state(cl, upid)
        val wasRunning = running
        running = state.running
        exitStatus = state.exitStatus
        // Follow new lines of a running task while the end of the log is in view.
        val follow = state.running && atEnd
        val before = lines.size
        if (!truncated) readMore(cl)
        // The list entry has the end time; read it once the task has finished.
        if (!state.running && (entry == null || entry?.end == 0L || wasRunning == true)) {
            entry = runCatching { TaskList.find(cl, upid) }.getOrNull() ?: entry
        }
        if (follow && lines.size > before) {
            // Item 0 is the summary; with wrapping each line is its own item, without it one block.
            listState.scrollToItem(if (wrap) lines.size else 1)
        }
        error = null
        true
    } catch (e: PveError) {
        error = e
        false
    }

    // Live while running; a finished task is read once and then only on demand.
    PollWhileVisible(listOf(upid, server?.id, running == false), if (running == false) 3_600_000L else LIVE_POLL_MS) {
        val cl = client ?: return@PollWhileVisible false
        refresh(cl)
    }

    val type = entry?.type ?: decoded?.type.orEmpty()
    val id = entry?.id ?: decoded?.id.orEmpty()
    val vmid = id.toIntOrNull()?.takeIf { it >= 100 }
    val guestName = vmid?.let { v -> guests.guests.firstOrNull { it.vmid == v }?.name }
    val title = taskDescription(context, type, id, guestName)
    val node = entry?.node ?: decoded?.node.orEmpty()
    val authId = server?.authId.orEmpty()
    val owned = entry?.isOwnedBy(authId) ?: (decoded?.user?.let { it == authId || authId.substringBefore('!') == it } == true)
    val canStop = running == true && server?.readOnly == false && (owned || c.sessions.canWrite("/nodes/$node", TaskList.STOP_PRIV))

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(title) {
                IconButton(onClick = { wrap = !wrap }) {
                    Icon(
                        Icons.AutoMirrored.Filled.WrapText,
                        stringResource(R.string.tasks_wrap),
                        tint = if (wrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    copySensitive(context, "ProxCtrl task log", lines.joinToString("\n") { it.text })
                    Toast.makeText(context, context.getString(R.string.tasks_log_copied), Toast.LENGTH_SHORT).show()
                }, enabled = lines.isNotEmpty()) {
                    Icon(Icons.Filled.ContentCopy, stringResource(R.string.tasks_copy_log))
                }
            }
            if (access.error != null) ConnectionBanner()
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = navBottom + 72.dp),
            ) {
                item(key = "summary") {
                    Summary(
                        running = running,
                        exitStatus = exitStatus ?: entry?.status?.takeIf { it.isNotBlank() && it != "RUNNING" },
                        node = node,
                        user = entry?.fullUser ?: decoded?.user.orEmpty(),
                        start = entry?.start ?: decoded?.start ?: 0L,
                        end = entry?.end ?: 0L,
                        canStop = canStop,
                        onStop = { confirmStop = true },
                    )
                    error?.let {
                        Text(
                            errorMessage(context, it, server?.host.orEmpty(), server?.auth),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.tasks_log),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Gutter + 4.dp, top = 12.dp, bottom = 6.dp),
                    )
                    if (lines.isEmpty()) {
                        Text(
                            stringResource(if (running == null) R.string.tasks_log_loading else R.string.tasks_log_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Gutter + 4.dp),
                        )
                    }
                }
                if (wrap) {
                    items(lines, key = { "l" + it.n }) { line -> LogText(line.text, Modifier.padding(horizontal = Gutter)) }
                } else {
                    item(key = "nowrap") {
                        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter)) {
                            lines.forEach { LogText(it.text, Modifier, softWrap = false) }
                        }
                    }
                }
                if (truncated) {
                    item(key = "truncated") {
                        Text(
                            stringResource(R.string.tasks_log_truncated, MAX_LINES),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Gutter),
                        )
                    }
                }
            }
        }
        if (!atEnd && lines.isNotEmpty()) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            ) { Icon(Icons.Filled.KeyboardDoubleArrowDown, stringResource(R.string.tasks_jump_end)) }
        }
    }

    if (confirmStop) {
        ConfirmDialog(
            title = stringResource(R.string.tasks_stop_title),
            body = stringResource(R.string.tasks_stop_body, title),
            confirmLabel = stringResource(R.string.tasks_stop),
            onDismiss = { confirmStop = false },
            onConfirm = {
                confirmStop = false
                val cl = client ?: return@ConfirmDialog
                scope.launch {
                    val message = try {
                        TaskList.stop(cl, upid)
                        context.getString(R.string.tasks_stop_sent)
                    } catch (e: PveError) {
                        errorMessage(context, e, server?.host.orEmpty(), server?.auth)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    refresh(cl)
                }
            },
        )
    }
}

@Composable
private fun LogText(text: String, modifier: Modifier, softWrap: Boolean = true) {
    Text(
        text.ifEmpty { " " },
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        softWrap = softWrap,
        color = logLineColor(text),
        modifier = modifier,
    )
}

@Composable
private fun Summary(
    running: Boolean?,
    exitStatus: String?,
    node: String,
    user: String,
    start: Long,
    end: Long,
    canStop: Boolean,
    onStop: () -> Unit,
) {
    val ok = exitStatus == "OK"
    val warnings = exitStatus?.startsWith("WARNINGS") == true
    val (bg, fg) = when {
        running == null -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurface
        running == true -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ok -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        warnings -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = bg,
        contentColor = fg,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running == null || running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = fg)
                Spacer(Modifier.width(if (running == null || running) 12.dp else 0.dp))
                Text(
                    when {
                        running == null -> stringResource(R.string.tasks_log_loading)
                        running == true -> stringResource(R.string.tasks_state_running)
                        ok -> stringResource(R.string.tasks_state_ok)
                        warnings -> stringResource(R.string.tasks_state_warnings, exitStatus.orEmpty())
                        else -> stringResource(R.string.tasks_state_failed)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (canStop) {
                    FilledTonalButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.tasks_stop))
                    }
                }
            }
            if (running == false && !ok && !warnings && !exitStatus.isNullOrBlank()) {
                Spacer(Modifier.size(6.dp))
                Text(exitStatus, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.size(10.dp))
            DetailLine(stringResource(R.string.tasks_detail_node), node)
            DetailLine(stringResource(R.string.tasks_detail_user), user)
            if (start > 0) DetailLine(stringResource(R.string.tasks_detail_started), formatDateTime(start))
            if (end > 0) DetailLine(stringResource(R.string.tasks_detail_ended), formatDateTime(end))
            if (start > 0) {
                val now = System.currentTimeMillis() / 1000
                val duration = (if (end > 0) end else if (running == true) now else 0L) - start
                if (duration >= 0 && (end > 0 || running == true)) DetailLine(stringResource(R.string.tasks_detail_duration), formatDuration(duration))
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
