package com.vknxworks.proxctrl.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.vknxworks.proxctrl.ui.components.CtrlCard
import com.vknxworks.proxctrl.ui.components.CtrlHeroCard
import com.vknxworks.proxctrl.ui.components.CtrlListRow
import com.vknxworks.proxctrl.ui.components.CtrlSectionLabel
import com.vknxworks.proxctrl.ui.components.CtrlSquareButton
import com.vknxworks.proxctrl.ui.theme.CtrlColors
import com.vknxworks.proxctrl.ui.theme.CtrlType
import com.vknxworks.proxctrl.ui.theme.Health
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
        // The node's name is the headline here, so the tab title isn't repeated.
        TabTitle(stringResource(R.string.tab_overview), showTitle = false)
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
                items(state.nodes, key = { "node-" + it.node }) { node ->
                    NodeSection(
                        node = node,
                        status = state.nodeStatus[node.node],
                        rrd = state.rrd[node.node].orEmpty(),
                        diskWarn = settings.diskWarnPercent,
                        memWarn = settings.memWarnPercent,
                        onClick = { nav.tab(NodeRoute) },
                        onShell = if (node.isOnline && c.sessions.canWrite("/nodes/${node.node}", "Sys.Console")) {
                            {
                                nav.navigate(ConsoleRoute(kind = "node", node = node.node, title = shellTitle.replace("%s", node.node)))
                            }
                        } else {
                            null
                        },
                    )
                }
                if (state.updatedAt > 0) {
                    item(key = "attention") { AttentionCard(attention, server?.name.orEmpty(), open) }
                }
                if (guests.guests.isNotEmpty()) {
                    val all = guests.guests.filter { !it.isTemplate }
                    item(key = "guests") {
                        GuestsHeader(all.count { it.isRunning }, all.size) { nav.tab(GuestsRoute) }
                    }
                    items(all.sortedBy { it.vmid }.take(GUESTS_SHOWN), key = { "guest-" + it.node + "-" + it.vmid }) { g ->
                        CtrlListRow(
                            title = g.name.ifBlank { "—" },
                            meta = g.vmid.toString(),
                            status = stringResource(
                                when {
                                    g.isRunning -> R.string.signal_state_run
                                    g.status == "paused" || g.status == "suspended" -> R.string.signal_state_pause
                                    else -> R.string.signal_state_stop
                                },
                            ),
                            active = g.isRunning,
                            statusColor = when {
                                g.isRunning -> CtrlColors.AccentText
                                g.status == "paused" || g.status == "suspended" -> Health.warn
                                else -> CtrlColors.Stopped
                            },
                            modifier = Modifier.padding(horizontal = Gutter),
                            onClick = { nav.navigate(GuestRoute(g.node, g.type.api, g.vmid)) },
                        )
                    }
                }
            }
        }
    }
}

private const val GUESTS_SHOWN = 6

@Composable
private fun GuestsHeader(running: Int, total: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clickable(onClickLabel = stringResource(R.string.overview_guests_line, running, total), onClick = onClick)
            .padding(horizontal = Gutter + 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.tab_guests), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.signal_guests_running, running, total).uppercase(), style = CtrlType.MonoStatus, color = CtrlColors.Muted)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = CtrlColors.Muted)
    }
}

/**
 * One node, as in the Signal render: a mono label and the node's name as the headline, the CPU load
 * as the accent hero card, memory and root disk as two cards, then the rest of the details.
 */
@Composable
private fun NodeSection(
    node: NodeEntry,
    status: NodeStatus?,
    rrd: List<RrdPoint>,
    diskWarn: Int,
    memWarn: Int,
    onClick: () -> Unit,
    onShell: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CtrlSectionLabel(
                    stringResource(R.string.signal_node_label, stringResource(if (node.isOnline) R.string.node_online else R.string.node_offline)),
                    color = if (node.isOnline) CtrlColors.Muted else CtrlColors.Error,
                )
                Text(node.node, style = CtrlType.Display, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        status?.pveVersion?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.pve_version, it) },
                        if (node.isOnline) stringResource(R.string.uptime_value, formatUptime(status?.uptime ?: node.uptime.toLong())) else null,
                    ).joinToString(" · ").lowercase(),
                    style = CtrlType.Mono,
                    color = CtrlColors.Muted,
                )
            }
            if (onShell != null) {
                CtrlSquareButton(Icons.Outlined.Terminal, stringResource(R.string.node_shell), onClick = onShell)
            }
        }
        if (status != null) NodeDetails(node, status, rrd, diskWarn, memWarn, onClick)
    }
}

@Composable
private fun NodeDetails(node: NodeEntry, status: NodeStatus, rrd: List<RrdPoint>, diskWarn: Int, memWarn: Int, onClick: () -> Unit) {
    val cpus = status.cpus.takeIf { it > 0 } ?: node.maxcpu.toInt()
    val cpuHistory = rrd.takeLast(24).map { p -> p.values["cpu"] }
    CtrlHeroCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.signal_cpu_load).uppercase(), style = CtrlType.Label.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            if (cpus > 0) Text(stringResource(R.string.signal_threads, cpus).uppercase(), style = CtrlType.Label.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp))
        }
        Text(
            buildAnnotatedString {
                append(percent(status.cpu).toString())
                withStyle(SpanStyle(fontSize = 28.sp, letterSpacing = 0.sp)) { append("%") }
            },
            style = CtrlType.BigNumber,
        )
        if (cpuHistory.count { it != null } >= 2) CpuBars(cpuHistory)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(
            label = stringResource(R.string.signal_memory),
            used = formatBytes(status.memUsed),
            total = formatBytes(status.memTotal),
            fraction = status.memFraction,
            color = usageColor(status.memFraction, memWarn, CtrlColors.Text),
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = stringResource(R.string.signal_root_disk),
            used = formatBytes(status.rootUsed),
            total = formatBytes(status.rootTotal),
            fraction = status.rootFraction,
            color = usageColor(status.rootFraction, diskWarn, CtrlColors.Text),
            modifier = Modifier.weight(1f),
        )
    }
    CtrlCard(onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CtrlSectionLabel(stringResource(R.string.signal_details))
            Text(
                listOfNotNull(
                    status.kernel.takeIf { it.isNotBlank() }?.let { stringResource(R.string.node_kernel, it) },
                    stringResource(R.string.node_io_delay, String.format(Locale.getDefault(), "%.1f", status.ioWait * 100)),
                    stringResource(R.string.node_load, status.loadAvg.joinToString("  ") { String.format(Locale.getDefault(), "%.2f", it) }),
                ).joinToString("\n"),
                style = CtrlType.Mono,
                color = CtrlColors.Muted,
            )
            if (status.swapTotal > 0) {
                Meter(
                    stringResource(R.string.label_swap),
                    stringResource(R.string.row_mem_detail, formatBytes(status.swapUsed), formatBytes(status.swapTotal)),
                    status.swapFraction,
                    CtrlColors.Text,
                )
            }
            if (rrd.size >= 2) {
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
}

/** The CPU history as small bars on the hero card; the latest few are drawn solid. */
@Composable
private fun CpuBars(values: List<Double?>) {
    val ink = CtrlColors.OnAccent
    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        val n = values.size
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val r = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        values.forEachIndexed { i, v ->
            val f = (v ?: 0.0).coerceIn(0.0, 1.0).toFloat()
            val h = (size.height * f).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                ink.copy(alpha = if (i >= n - 4) 1f else 0.55f),
                Offset(i * (w + gap), size.height - h),
                Size(w, h),
                r,
            )
        }
    }
}

@Composable
private fun StatCard(label: String, used: String, total: String, fraction: Double?, color: Color, modifier: Modifier = Modifier) {
    CtrlCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CtrlSectionLabel(label)
            Text(
                buildAnnotatedString {
                    append(used)
                    withStyle(SpanStyle(fontSize = 13.sp, color = CtrlColors.Muted, letterSpacing = 0.sp)) { append(" / $total") }
                },
                style = CtrlType.Readout.copy(fontSize = 22.sp),
                maxLines = 2,
            )
            UsageBar(fraction, color)
        }
    }
}

@Composable
private fun Meter(label: String, value: String, fraction: Double?, color: Color) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(value, style = CtrlType.Mono, color = CtrlColors.Muted)
            if (fraction != null) {
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.percent, percent(fraction)), style = CtrlType.Mono.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        Spacer(Modifier.height(6.dp))
        UsageBar(fraction, color)
    }
}
