package com.vknxworks.proxctrl.ui.guests

import com.vknxworks.proxctrl.ui.components.CtrlTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewHeadline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Badge
import androidx.compose.material3.FloatingActionButton
import com.vknxworks.proxctrl.ui.theme.CtrlColors
import com.vknxworks.proxctrl.ui.theme.CtrlShapes
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.guests.PowerAction
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.EmptyState
import com.vknxworks.proxctrl.ui.components.LocalBottomClearance
import com.vknxworks.proxctrl.ui.components.PollWhileVisible
import com.vknxworks.proxctrl.ui.components.StaleBanner
import com.vknxworks.proxctrl.ui.components.TabTitle
import com.vknxworks.proxctrl.ui.nav.GuestRoute
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

/** How often the guest list refreshes while it's on screen. */
private const val POLL_MS = 5_000L

/**
 * All containers and VMs, with the same information as the Proxmox resource view: type, ID and
 * name, status, CPU, memory and disk use, uptime and tags. Search, sort, filter, a compact view
 * for long lists, and bulk start / shutdown / reboot / stop by long-pressing a guest.
 */
@Composable
fun GuestsScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val state by c.guests.state.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val server = active?.server

    var filter by remember { mutableStateOf(GuestFilter()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<Int>()) }
    var pendingAction by remember { mutableStateOf<PowerAction?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    PollWhileVisible(server?.id, POLL_MS) {
        c.guests.refresh()
        c.guests.state.value.error == null
    }

    val all = state.guests
    val shown = remember(all, filter, settings.guestSort, settings.guestSortDescending) {
        all.filter(filter::matches).sortedFor(settings.guestSort, settings.guestSortDescending)
    }
    val nodes = remember(all) { all.map { it.node }.distinct().sorted() }
    val allTags = remember(all) { all.flatMap { it.tags }.distinct().sortedBy { it.lowercase() } }

    // Bulk actions need VM.PowerMgmt somewhere and read-only mode off.
    val canBulk = server != null && !server.readOnly && access.permissions.hasAnywhere("/vms", PowerAction.PRIV)
    val selecting = selection.isNotEmpty()
    // Selected guests that disappeared (deleted) drop out of the selection.
    val selectedGuests = all.filter { it.vmid in selection }

    fun toggle(g: Guest) {
        selection = if (g.vmid in selection) selection - g.vmid else selection + g.vmid
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (selecting) {
                SelectionBar(
                    count = selectedGuests.size,
                    onClose = { selection = emptySet() },
                    onSelectAll = { selection = shown.filterNot { it.isTemplate }.map { it.vmid }.toSet() },
                    onAction = { pendingAction = it },
                )
            } else {
                TabTitle(stringResource(R.string.tab_guests)) {
                    IconButton(onClick = { searching = !searching; if (!searching) filter = filter.copy(query = "") }) {
                        Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, stringResource(if (searching) R.string.close_search else R.string.search))
                    }
                    IconButton(onClick = { c.appScope.launch { c.settings.update { it.copy(guestCompact = !it.guestCompact) } } }) {
                        Icon(
                            if (settings.guestCompact) Icons.Outlined.ViewAgenda else Icons.Outlined.ViewHeadline,
                            stringResource(if (settings.guestCompact) R.string.view_detailed else R.string.view_compact),
                        )
                    }
                    IconButton(onClick = { sheetOpen = true }) {
                        BadgedBox(badge = { if (filter.isActive) Badge() }) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.sort_and_filter))
                        }
                    }
                }
            }
            if (searching && !selecting) {
                CtrlTextField(
                    value = filter.query,
                    onValueChange = { filter = filter.copy(query = it) },
                    placeholder = { Text(stringResource(R.string.search_guests)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp),
                )
            }

            val refreshError = state.error
            if (access.error != null) {
                ConnectionBanner()
            } else if (refreshError != null) {
                StaleBanner(state.updatedAt, refreshError, server?.host.orEmpty(), server?.auth) {
                    scope.launch { c.guests.refresh() }
                }
            }

            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    scope.launch {
                        c.guests.refresh()
                        refreshing = false
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                when {
                    all.isEmpty() && state.updatedAt > 0 -> EmptyState(
                        Icons.Outlined.Computer,
                        stringResource(R.string.guests_none),
                        if (access.permissions.hasAnywhere("/vms", "VM.Audit") || access.loading) {
                            stringResource(R.string.guests_none_body, server?.name.orEmpty())
                        } else {
                            stringResource(R.string.guests_cant_see)
                        },
                        Modifier.padding(bottom = LocalBottomClearance.current),
                    )
                    all.isNotEmpty() && shown.isEmpty() -> EmptyState(
                        Icons.Outlined.SearchOff,
                        stringResource(R.string.guests_no_match),
                        stringResource(R.string.guests_no_match_body),
                        Modifier.padding(bottom = LocalBottomClearance.current),
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = LocalBottomClearance.current + 88.dp),
                    ) {
                        item(key = "summary") {
                            SummaryLine(all, shown.size)
                        }
                        if (settings.guestCompact) item(key = "header") { CompactHeader() }
                        items(shown, key = { it.vmid }) { g ->
                            val selected = g.vmid in selection
                            val onClick = {
                                if (selecting) {
                                    if (!g.isTemplate) toggle(g)
                                } else {
                                    nav.navigate(GuestRoute(g.node, g.type.api, g.vmid))
                                }
                            }
                            val onLongClick: (() -> Unit)? = if (canBulk && !g.isTemplate) {
                                { toggle(g) }
                            } else {
                                null
                            }
                            if (settings.guestCompact) {
                                GuestRowCompact(g, settings.diskWarnPercent, selecting, selected, onClick, onLongClick)
                            } else {
                                GuestRowDetailed(g, state.tagColors, settings.diskWarnPercent, selecting, selected, onClick, onLongClick)
                            }
                        }
                    }
                }
            }
        }
        if (server != null && !server.readOnly && !selecting) {
            FloatingActionButton(
                onClick = { nav.navigate(CreateGuestRoute) },
                containerColor = CtrlColors.Accent,
                contentColor = CtrlColors.OnAccent,
                shape = CtrlShapes.Button,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = Gutter, bottom = LocalBottomClearance.current + 12.dp),
            ) {
                Icon(Icons.Outlined.Add, stringResource(R.string.create_title))
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomClearance.current + 8.dp),
        )
    }

    if (sheetOpen) {
        SortFilterSheet(
            filter = filter,
            sort = settings.guestSort,
            descending = settings.guestSortDescending,
            nodes = nodes,
            tags = allTags,
            tagColors = state.tagColors,
            onFilter = { filter = it },
            onSort = { sort, desc -> c.appScope.launch { c.settings.update { it.copy(guestSort = sort, guestSortDescending = desc) } } },
            onDismiss = { sheetOpen = false },
        )
    }

    pendingAction?.let { action ->
        val client = active?.client
        BulkPowerDialog(
            action = action,
            selected = selectedGuests,
            canRun = { g -> action.appliesTo(g) && c.sessions.canWrite(g.aclPath, PowerAction.PRIV) },
            onDismiss = { pendingAction = null },
            onConfirm = { targets ->
                pendingAction = null
                selection = emptySet()
                if (client != null) {
                    scope.launch {
                        val message = runBulk(context, client, action, targets)
                        c.guests.refresh()
                        snackbar.showSnackbar(message)
                    }
                }
            },
        )
    }
}

@Composable
private fun SummaryLine(all: List<Guest>, shown: Int) {
    val lxc = all.count { it.type == GuestType.LXC && !it.isTemplate }
    val vms = all.count { it.type == GuestType.QEMU && !it.isTemplate }
    val running = all.count { it.isRunning }
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.guests_summary, lxc, vms, running), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (shown != all.size) {
            Text(stringResource(R.string.guests_filtered, shown, all.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit, onAction: (PowerAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_selection)) }
            Text(stringResource(R.string.selected_count, count), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.select_all)) }
        }
        BulkActionRow(onAction)
    }
}
