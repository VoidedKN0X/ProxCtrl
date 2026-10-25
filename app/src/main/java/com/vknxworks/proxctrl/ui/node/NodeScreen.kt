package com.vknxworks.proxctrl.ui.node

import com.vknxworks.proxctrl.ui.components.CtrlSecondaryButton
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.guests.RrdPoint
import com.vknxworks.proxctrl.data.guests.Timeframe
import com.vknxworks.proxctrl.data.node.KernelState
import com.vknxworks.proxctrl.data.node.NodeApi
import com.vknxworks.proxctrl.data.node.WakeOnLan
import com.vknxworks.proxctrl.data.node.WolConfig
import com.vknxworks.proxctrl.data.overview.NodeStatus
import com.vknxworks.proxctrl.ui.components.ChartSeries
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.LineChart
import com.vknxworks.proxctrl.ui.components.LocalBottomClearance
import com.vknxworks.proxctrl.ui.components.PollWhileVisible
import com.vknxworks.proxctrl.ui.components.StaleBanner
import com.vknxworks.proxctrl.ui.components.StatusDot
import com.vknxworks.proxctrl.ui.components.TabTitle
import com.vknxworks.proxctrl.ui.components.TaskCard
import com.vknxworks.proxctrl.ui.components.TypedConfirmDialog
import com.vknxworks.proxctrl.ui.components.UsageBar
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.components.formatUptime
import com.vknxworks.proxctrl.ui.components.percent
import com.vknxworks.proxctrl.ui.components.rememberTaskTracker
import com.vknxworks.proxctrl.ui.components.usageColor
import com.vknxworks.proxctrl.ui.nav.ConsoleRoute
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.coroutines.launch
import java.util.Locale

private const val STATUS_POLL_MS = 5_000L
private const val RRD_POLL_MS = 60_000L
private const val UPDATES_POLL_MS = 300_000L

/**
 * The node: health, hardware and versions, graphs, and the ways in (shell, services, updates,
 * network), plus reboot, shutdown and Wake-on-LAN. With several nodes, chips pick which one.
 */
@Composable
fun NodeScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val overview by c.overview.state.collectAsStateWithLifecycle()
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val tracker = rememberTaskTracker()

    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val nodes = overview.nodes
    val node: String? = picked?.takeIf { p -> nodes.any { it.node == p } } ?: nodes.firstOrNull()?.node
    val entry = nodes.firstOrNull { it.node == node }

    var status by remember(node, server?.id) { mutableStateOf<NodeStatus?>(node?.let { overview.nodeStatus[it] }) }
    var statusError by remember(node, server?.id) { mutableStateOf<PveError?>(null) }
    var statusAt by remember(node, server?.id) { mutableLongStateOf(0L) }
    var timeframe by rememberSaveable { mutableStateOf(Timeframe.HOUR) }
    var rrd by remember(node, server?.id) { mutableStateOf<List<RrdPoint>>(emptyList()) }
    var updateCount by remember(node, server?.id) { mutableStateOf<Int?>(null) }
    var kernel by remember(node, server?.id) { mutableStateOf<KernelState?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var wol by remember(server?.id) { mutableStateOf(server?.id?.let { WakeOnLan.load(context, it) }) }
    var editingWol by remember { mutableStateOf(false) }
    var powerAction by remember { mutableStateOf<Boolean?>(null) } // true = reboot, false = shutdown
    var menu by remember { mutableStateOf(false) }

    suspend fun loadStatus(): Boolean {
        val cl = client ?: return false
        c.overview.refresh()
        val n = node ?: return c.overview.state.value.error == null
        return try {
            status = NodeApi.status(cl, n)
            statusAt = System.currentTimeMillis()
            statusError = null
            true
        } catch (e: PveError) {
            statusError = e
            false
        }
    }

    suspend fun loadUpdates() {
        val cl = client ?: return
        val n = node ?: return
        val path = NodeApi.aclPath(n)
        if (access.permissions.has(path, NodeApi.PRIV_MODIFY)) {
            runCatching { NodeApi.updates(cl, n) }.onSuccess { updateCount = it.size }
        }
        if (access.permissions.has(path, NodeApi.PRIV_AUDIT)) {
            runCatching { NodeApi.kernelState(cl, n) }.onSuccess { kernel = it }
        }
    }

    PollWhileVisible(Pair(server?.id, node), STATUS_POLL_MS) { loadStatus() }
    PollWhileVisible(Triple(server?.id, node, timeframe), RRD_POLL_MS) {
        val cl = client ?: return@PollWhileVisible false
        val n = node ?: return@PollWhileVisible false
        runCatching { NodeApi.rrd(cl, n, timeframe) }.onSuccess { rrd = it }.isSuccess
    }
    PollWhileVisible(Triple(server?.id, node, access.permissions), UPDATES_POLL_MS) {
        loadUpdates()
        true
    }

    // The node can't be reached at all (or the cluster says it's offline): that's when waking it helps.
    val unreachable = statusError is PveError.Unreachable ||
        (statusError == null && overview.error is PveError.Unreachable) ||
        entry?.isOnline == false
    val online = entry?.isOnline == true && statusError == null
    val path = node?.let(NodeApi::aclPath).orEmpty()
    val canPower = node != null && c.sessions.canWrite(path, NodeApi.PRIV_POWER)
    val canShell = node != null && online && c.sessions.canWrite(path, NodeApi.PRIV_CONSOLE)
    val canAudit = node != null && access.permissions.has(path, NodeApi.PRIV_AUDIT)
    val canListUpdates = node != null && access.permissions.has(path, NodeApi.PRIV_MODIFY)

    fun wake() {
        val config = wol ?: run { editingWol = true; return }
        scope.launch {
            val ok = runCatching { WakeOnLan.send(config) }.isSuccess
            Toast.makeText(
                context,
                if (ok) context.getString(R.string.node_wol_sent, config.mac) else context.getString(R.string.node_wol_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabTitle(
            stringResource(R.string.tab_node),
            trailing = {
                if (server != null) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_actions)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (wol == null) R.string.node_wol_setup else R.string.node_wol_wake)) },
                                leadingIcon = { Icon(Icons.Outlined.PowerSettingsNew, null) },
                                onClick = { menu = false; if (wol == null) editingWol = true else wake() },
                            )
                            if (wol != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.node_wol_settings)) },
                                    leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                    onClick = { menu = false; editingWol = true },
                                )
                            }
                        }
                    }
                }
            },
        )
        val err = statusError
        if (access.error != null) {
            ConnectionBanner()
        } else if (err != null) {
            StaleBanner(statusAt, err, server?.host.orEmpty(), server?.auth) { scope.launch { loadStatus() } }
        }
        if (nodes.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                nodes.forEach { n ->
                    FilterChip(
                        selected = n.node == node,
                        onClick = { picked = n.node },
                        label = { Text(n.node) },
                        leadingIcon = { StatusDot(n.status, size = 8.dp) },
                    )
                }
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    c.overview.refresh(force = true)
                    loadStatus()
                    loadUpdates()
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalBottomClearance.current + 16.dp),
            ) {
                if (unreachable && server != null) {
                    WakeCard(node ?: server.name, wol != null, onWake = ::wake, onSetUp = { editingWol = true })
                }
                TaskCard(tracker)

                if (node == null && !unreachable) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                if (node != null) {
                NodeHeader(node, entry?.status ?: if (unreachable) "offline" else "unknown", status)

                val st = status
                if (st != null) {
                    UsageGroup(st, settings.diskWarnPercent, settings.memWarnPercent)
                    DetailsGroup(st)
                }

                Group(stringResource(R.string.node_group_manage)) {
                    if (canShell) {
                        GroupRow(
                            title = stringResource(R.string.node_shell),
                            subtitle = stringResource(R.string.node_shell_subtitle),
                            icon = Icons.Outlined.Terminal,
                            showChevron = true,
                            onClick = { nav.navigate(ConsoleRoute(kind = "node", node = node, title = context.getString(R.string.node_shell_title, node))) },
                        )
                    }
                    if (canAudit) {
                        GroupRow(
                            title = stringResource(R.string.node_services),
                            subtitle = stringResource(R.string.node_services_subtitle),
                            icon = Icons.Outlined.Tune,
                            showChevron = true,
                            onClick = { nav.navigate(NodeServicesRoute(node)) },
                        )
                    }
                    if (canListUpdates || canAudit) {
                        val kernelNote = kernel?.takeIf { it.rebootRequired }
                        GroupRow(
                            title = stringResource(R.string.node_updates),
                            subtitle = listOfNotNull(
                                when (val count = updateCount) {
                                    null -> if (canListUpdates) "…" else null
                                    0 -> stringResource(R.string.node_updates_none)
                                    else -> pluralStringResource(R.plurals.node_updates_count, count, count)
                                },
                                kernelNote?.let { stringResource(R.string.node_reboot_required_short) },
                            ).joinToString(" · "),
                            icon = Icons.Outlined.SystemUpdate,
                            iconTint = if (kernelNote != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            showChevron = true,
                            onClick = { nav.navigate(NodeUpdatesRoute(node)) },
                        )
                    }
                    GroupRow(
                        title = stringResource(R.string.node_network),
                        subtitle = stringResource(R.string.node_network_subtitle),
                        icon = Icons.Outlined.Hub,
                        showChevron = true,
                        onClick = { nav.navigate(NodeNetworkRoute(node)) },
                    )
                }

                NodeGraphs(rrd, timeframe, onTimeframe = { timeframe = it; rrd = emptyList() })

                if (server?.readOnly == false) {
                    Group(stringResource(R.string.node_group_power), footer = if (!canPower) stringResource(R.string.not_allowed_action) else null) {
                        GroupRow(
                            title = stringResource(R.string.node_reboot),
                            subtitle = stringResource(R.string.node_reboot_subtitle),
                            icon = Icons.Outlined.RestartAlt,
                            iconTint = MaterialTheme.colorScheme.error,
                            onClick = if (canPower && online && !tracker.busy) ({ powerAction = true }) else null,
                        )
                        GroupRow(
                            title = stringResource(R.string.node_shutdown),
                            subtitle = stringResource(R.string.node_shutdown_subtitle),
                            icon = Icons.Outlined.PowerSettingsNew,
                            iconTint = MaterialTheme.colorScheme.error,
                            onClick = if (canPower && online && !tracker.busy) ({ powerAction = false }) else null,
                        )
                    }
                }
                }
            }
        }
    }

    if (editingWol && server != null) {
        WakeOnLanDialog(
            initial = wol,
            onDismiss = { editingWol = false },
            onSave = { config ->
                WakeOnLan.save(context, server.id, config)
                wol = config
                editingWol = false
            },
        )
    }

    val pendingPower = powerAction
    if (pendingPower != null && node != null) {
        val reboot = pendingPower
        val n = node
        TypedConfirmDialog(
            title = stringResource(if (reboot) R.string.node_reboot_title else R.string.node_shutdown_title, n),
            body = buildString {
                append(context.getString(if (reboot) R.string.node_reboot_body else R.string.node_shutdown_body))
                if (!reboot) {
                    append("\n\n")
                    append(context.getString(if (wol != null) R.string.node_shutdown_wol_ready else R.string.node_shutdown_no_wol))
                }
            },
            expected = n,
            confirmLabel = stringResource(if (reboot) R.string.node_reboot else R.string.node_shutdown),
            icon = if (reboot) Icons.Outlined.RestartAlt else Icons.Outlined.PowerSettingsNew,
            onDismiss = { powerAction = null },
            onConfirm = {
                powerAction = null
                val cl = client ?: return@TypedConfirmDialog
                val label = context.getString(if (reboot) R.string.node_task_reboot else R.string.node_task_shutdown, n)
                tracker.run(label, cl, server?.host.orEmpty(), server?.auth) {
                    NodeApi.power(cl, n, reboot)
                    null
                }
            },
        )
    }
}

/** Shown when the node can't be reached: wake it, or set Wake-on-LAN up first. */
@Composable
private fun WakeCard(name: String, configured: Boolean, onWake: () -> Unit, onSetUp: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, com.vknxworks.proxctrl.ui.theme.CtrlColors.Outline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PowerSettingsNew, null, Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.node_unreachable_title, name), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(if (configured) R.string.node_unreachable_wol else R.string.node_unreachable_no_wol),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            CtrlSecondaryButton(onClick = if (configured) onWake else onSetUp) {
                Icon(Icons.Outlined.PowerSettingsNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (configured) R.string.node_wol_wake else R.string.node_wol_setup))
            }
        }
    }
}

@Composable
private fun NodeHeader(node: String, state: String, status: NodeStatus?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(state, size = 12.dp)
            Spacer(Modifier.width(10.dp))
            Text(node, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (status != null && status.uptime > 0) {
                Text(
                    stringResource(R.string.uptime_value, formatUptime(status.uptime)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state == "offline") {
                Text(stringResource(R.string.node_offline), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (status != null) {
            Text(
                listOfNotNull(
                    status.pveVersion.takeIf { it.isNotBlank() }?.let { stringResource(R.string.pve_version, it) },
                    status.kernel.takeIf { it.isNotBlank() }?.let { stringResource(R.string.node_kernel, it) },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp),
            )
        }
    }
}

@Composable
private fun UsageGroup(st: NodeStatus, diskWarn: Int, memWarn: Int) {
    val accent = MaterialTheme.colorScheme.primary
    Group(stringResource(R.string.group_overview)) {
        UsageLine(
            stringResource(R.string.label_cpu),
            stringResource(R.string.node_cpu_summary, percent(st.cpu), st.cpus),
            st.cpu,
            accent,
        )
        UsageLine(
            stringResource(R.string.label_mem),
            stringResource(R.string.row_mem_detail, formatBytes(st.memUsed), formatBytes(st.memTotal)),
            st.memFraction,
            usageColor(st.memFraction, memWarn, accent),
        )
        if (st.swapTotal > 0) {
            UsageLine(
                stringResource(R.string.label_swap),
                stringResource(R.string.row_mem_detail, formatBytes(st.swapUsed), formatBytes(st.swapTotal)),
                st.swapFraction,
                accent,
            )
        }
        UsageLine(
            stringResource(R.string.label_root),
            stringResource(R.string.row_disk_detail, formatBytes(st.rootUsed), formatBytes(st.rootTotal)),
            st.rootFraction,
            usageColor(st.rootFraction, diskWarn, accent),
        )
    }
}

@Composable
private fun UsageLine(label: String, value: String, fraction: Double?, color: Color) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fraction != null) {
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.percent, percent(fraction)), style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(6.dp))
        UsageBar(fraction, color, height = 6.dp)
    }
}

@Composable
private fun DetailsGroup(st: NodeStatus) {
    Group(stringResource(R.string.group_details)) {
        if (st.cpuModel.isNotBlank()) GroupRow(stringResource(R.string.node_cpu_model), st.cpuModel)
        if (st.cpus > 0) {
            GroupRow(
                stringResource(R.string.node_cpu_layout),
                if (st.sockets > 0) stringResource(R.string.node_cpu_layout_value, st.sockets, st.cpus) else st.cpus.toString(),
            )
        }
        if (st.memTotal > 0) GroupRow(stringResource(R.string.node_ram_total), formatBytes(st.memTotal))
        GroupRow(
            stringResource(R.string.node_load_title),
            listOf(
                stringResource(R.string.node_load, st.loadAvg.joinToString("  ") { String.format(Locale.getDefault(), "%.2f", it) }),
                stringResource(R.string.node_io_delay, String.format(Locale.getDefault(), "%.1f", st.ioWait * 100)),
            ).joinToString("\n"),
        )
        if (st.kernel.isNotBlank()) GroupRow(stringResource(R.string.node_kernel_title), st.kernel)
        if (st.pveVersion.isNotBlank()) GroupRow(stringResource(R.string.node_pve_title), st.pveVersion)
        if (st.bootMode.isNotBlank()) {
            val mode = when (st.bootMode) {
                "efi" -> stringResource(if (st.secureBoot) R.string.node_boot_efi_secure else R.string.node_boot_efi)
                "legacy-bios" -> stringResource(R.string.node_boot_bios)
                else -> st.bootMode
            }
            GroupRow(stringResource(R.string.node_boot_title), mode)
        }
    }
}

@Composable
private fun NodeGraphs(rrd: List<RrdPoint>, timeframe: Timeframe, onTimeframe: (Timeframe) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
        Text(
            stringResource(R.string.group_graphs),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                Timeframe.HOUR to R.string.tf_hour,
                Timeframe.DAY to R.string.tf_day,
                Timeframe.WEEK to R.string.tf_week,
                Timeframe.MONTH to R.string.tf_month,
                Timeframe.YEAR to R.string.tf_year,
            ).forEach { (tf, label) ->
                FilterChip(selected = tf == timeframe, onClick = { onTimeframe(tf) }, label = { Text(stringResource(label)) })
            }
        }
        Spacer(Modifier.height(8.dp))
        if (rrd.isEmpty()) {
            Text(
                stringResource(R.string.chart_no_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            RrdCharts(rrd)
        }
    }
}

@Composable
private fun RrdCharts(rrd: List<RrdPoint>) {
    Column {
        val times = rrd.map { it.time }
        fun col(key: String, scale: Double = 1.0) = rrd.map { p -> p.values[key]?.let { it * scale } }
        val rateTemplate = stringResource(R.string.per_second, "%s")
        val rate: (Double) -> String = { v -> rateTemplate.replace("%s", formatBytes(v.toLong())) }
        val memTotal = rrd.mapNotNull { it.values["memtotal"] }.maxOrNull()

        ChartCard(stringResource(R.string.chart_cpu)) {
            LineChart(
                times,
                listOf(
                    ChartSeries(stringResource(R.string.series_cpu), col("cpu", 100.0)),
                    ChartSeries(stringResource(R.string.node_series_iowait), col("iowait", 100.0)),
                ),
                format = { "${it.toInt()}%" },
                yMax = 100.0,
            )
        }
        ChartCard(stringResource(R.string.node_chart_load)) {
            LineChart(
                times,
                listOf(ChartSeries(stringResource(R.string.node_series_load), col("loadavg"))),
                format = { String.format(Locale.getDefault(), "%.1f", it) },
            )
        }
        ChartCard(stringResource(R.string.chart_memory)) {
            LineChart(times, listOf(ChartSeries(stringResource(R.string.series_used), col("memused"))), format = { formatBytes(it.toLong()) }, yMax = memTotal)
        }
        ChartCard(stringResource(R.string.chart_network)) {
            LineChart(
                times,
                listOf(ChartSeries(stringResource(R.string.series_in), col("netin")), ChartSeries(stringResource(R.string.series_out), col("netout"))),
                format = rate,
            )
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, com.vknxworks.proxctrl.ui.theme.CtrlColors.Outline),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            com.vknxworks.proxctrl.ui.components.CtrlSectionLabel(title)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
