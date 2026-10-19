package com.vknxworks.proxctrl.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.guests.RrdPoint
import com.vknxworks.proxctrl.data.overview.Attention
import com.vknxworks.proxctrl.data.overview.NodeEntry
import com.vknxworks.proxctrl.data.overview.NodeStatus
import com.vknxworks.proxctrl.data.overview.Thresholds
import com.vknxworks.proxctrl.ui.components.ChartSeries
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.LocalBottomClearance
import com.vknxworks.proxctrl.ui.components.PollWhileVisible
import com.vknxworks.proxctrl.ui.components.Sparkline
import com.vknxworks.proxctrl.ui.components.StaleBanner
import com.vknxworks.proxctrl.ui.components.StatusDot
import com.vknxworks.proxctrl.ui.components.TabTitle
import com.vknxworks.proxctrl.ui.components.UsageBar
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.components.formatUptime
import com.vknxworks.proxctrl.ui.components.percent
import com.vknxworks.proxctrl.ui.components.usageColor
import com.vknxworks.proxctrl.ui.nav.ConsoleRoute
import com.vknxworks.proxctrl.ui.nav.GuestRoute
import com.vknxworks.proxctrl.ui.nav.GuestsRoute
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import com.vknxworks.proxctrl.ui.nav.NodeRoute
import com.vknxworks.proxctrl.ui.nav.StorageRoute
import com.vknxworks.proxctrl.ui.nav.TasksRoute
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.coroutines.launch
import java.util.Locale

private const val POLL_MS = 5_000L

/** The only summary screen: what needs attention first, then each node's health. */
@Composable
fun OverviewScreen() {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val state by c.overview.state.collectAsStateWithLifecycle()
    val guests by c.guests.state.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val server = active?.server
    var refreshing by remember { mutableStateOf(false) }

    PollWhileVisible(server?.id, POLL_MS) {
        c.overview.refresh()
        c.overview.state.value.error == null
    }

    val thresholds = Thresholds(settings.diskWarnPercent, settings.memWarnPercent, settings.backupMaxAgeDays)
    val attention = remember(state, guests.guests, thresholds) { Attention.compute(c.overview.attentionInput(), thresholds) }

    val shellTitle = stringResource(R.string.node_shell_title, "%s")

    val open: (AttentionTarget) -> Unit = { target ->
        when (target) {
            is AttentionTarget.GuestPage -> nav.navigate(GuestRoute(target.guest.node, target.guest.type.api, target.guest.vmid))
            AttentionTarget.Storage -> nav.tab(StorageRoute)
            AttentionTarget.Tasks -> nav.tab(TasksRoute)
            AttentionTarget.Node -> nav.tab(NodeRoute)
            AttentionTarget.Guests -> nav.tab(GuestsRoute)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabTitle(stringResource(R.string.tab_overview))
        val err = state.error
        if (access.error != null) {
            ConnectionBanner()
        } else if (err != null) {
            StaleBanner(state.updatedAt, err, server?.host.orEmpty(), server?.auth) { scope.launch { c.overview.refresh(force = true) } }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    c.overview.refresh(force = true)
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomClearance.current + 16.dp)) {
                if (state.updatedAt > 0) {
                    item(key = "attention") { AttentionCard(attention, server?.name.orEmpty(), open) }
                }
                if (guests.guests.isNotEmpty()) {
                    item(key = "guests") {
                        val all = guests.guests.filter { !it.isTemplate }
                        GuestsLine(all.count { it.isRunning }, all.size) { nav.tab(GuestsRoute) }
                    }
                }
                items(state.nodes, key = { "node-" + it.node }) { node ->
                    NodeCard(
                        node = node,
                        status = state.nodeStatus[node.node],
                        rrd = state.rrd[node.node].orEmpty(),
                        diskWarn = settings.diskWarnPercent,
                        memWarn = settings.memWarnPercent,
                        onClick = { nav.tab(NodeRoute) },
                        onShell = if (node.isOnline && c.sessions.canWrite("/nodes/${node.node}", "Sys.Console")) {
                            {
                                c.telemetry.track("console_opened", mapOf("kind" to "node_shell"))
                                nav.navigate(ConsoleRoute(kind = "node", node = node.node, title = shellTitle.replace("%s", node.node)))
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GuestsLine(running: Int, total: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Gutter + 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.overview_guests_line, running, total), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NodeCard(
    node: NodeEntry,
    status: NodeStatus?,
    rrd: List<RrdPoint>,
    diskWarn: Int,
    memWarn: Int,
    onClick: () -> Unit,
    onShell: (() -> Unit)?,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(node.status, size = 12.dp)
                Spacer(Modifier.width(10.dp))
                Text(node.node, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    if (node.isOnline) stringResource(R.string.uptime_value, formatUptime(status?.uptime ?: node.uptime.toLong()))
                    else stringResource(R.string.node_offline),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onShell != null) {
                    IconButton(onClick = onShell) { Icon(Icons.Filled.Terminal, stringResource(R.string.node_shell)) }
                }
            }
            if (status != null) NodeDetails(node, status, rrd, diskWarn, memWarn)
        }
    }
}

@Composable
private fun NodeDetails(node: NodeEntry, status: NodeStatus, rrd: List<RrdPoint>, diskWarn: Int, memWarn: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                listOfNotNull(
                    status.pveVersion.takeIf { it.isNotBlank() }?.let { stringResource(R.string.pve_version, it) },
                    status.kernel.takeIf { it.isNotBlank() }?.let { stringResource(R.string.node_kernel, it) },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val accent = MaterialTheme.colorScheme.primary
            val cpus = status.cpus.takeIf { it > 0 } ?: node.maxcpu.toInt()
            Meter(
                stringResource(R.string.label_cpu),
                stringResource(R.string.node_cpu_summary, percent(status.cpu), cpus),
                status.cpu,
                accent,
            )
            Text(
                listOf(
                    stringResource(R.string.node_io_delay, String.format(Locale.getDefault(), "%.1f", status.ioWait * 100)),
                    stringResource(R.string.node_load, status.loadAvg.joinToString("  ") { String.format(Locale.getDefault(), "%.2f", it) }),
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Meter(
                stringResource(R.string.label_mem),
                stringResource(R.string.row_mem_detail, formatBytes(status.memUsed), formatBytes(status.memTotal)),
                status.memFraction,
                usageColor(status.memFraction, memWarn, accent),
            )
            if (status.swapTotal > 0) {
                Meter(
                    stringResource(R.string.label_swap),
                    stringResource(R.string.row_mem_detail, formatBytes(status.swapUsed), formatBytes(status.swapTotal)),
                    status.swapFraction,
                    accent,
                )
            }
            Meter(
                stringResource(R.string.label_root),
                stringResource(R.string.row_disk_detail, formatBytes(status.rootUsed), formatBytes(status.rootTotal)),
                status.rootFraction,
                usageColor(status.rootFraction, diskWarn, accent),
            )
            if (rrd.size >= 2) {
                Spacer(Modifier.height(2.dp))
                Sparkline(
                    stringResource(R.string.sparkline_cpu),
                    listOf(ChartSeries(stringResource(R.string.series_cpu), rrd.map { p -> p.values["cpu"]?.let { it * 100 } })),
                    format = { "${it.toInt()}%" },
                    top = 100.0,
                )
                Sparkline(
                    stringResource(R.string.sparkline_mem),
                    listOf(ChartSeries(stringResource(R.string.series_used), rrd.map { it.values["memused"] })),
                    format = { formatBytes(it.toLong()) },
                    top = rrd.mapNotNull { it.values["memtotal"] }.maxOrNull(),
                )
                val rateTemplate = stringResource(R.string.per_second, "%s")
                Sparkline(
                    stringResource(R.string.sparkline_net),
                    listOf(
                        ChartSeries(stringResource(R.string.series_in), rrd.map { it.values["netin"] }),
                        ChartSeries(stringResource(R.string.series_out), rrd.map { it.values["netout"] }),
                    ),
                    format = { rateTemplate.replace("%s", formatBytes(it.toLong())) },
                )
            }
    }
}

@Composable
private fun Meter(label: String, value: String, fraction: Double?, color: Color) {
    Column {
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
