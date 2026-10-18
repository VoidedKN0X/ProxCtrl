package com.vknxworks.proxctrl.ui.guests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.guests.GuestAddress
import com.vknxworks.proxctrl.data.guests.GuestApi
import com.vknxworks.proxctrl.data.guests.GuestConfig
import com.vknxworks.proxctrl.data.guests.GuestRef
import com.vknxworks.proxctrl.data.guests.GuestStatus
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.guests.PowerAction
import com.vknxworks.proxctrl.data.guests.RrdPoint
import com.vknxworks.proxctrl.data.guests.Timeframe
import com.vknxworks.proxctrl.data.guests.power
import com.vknxworks.proxctrl.ui.components.ChartSeries
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.LineChart
import com.vknxworks.proxctrl.ui.components.MarkdownText
import com.vknxworks.proxctrl.ui.components.PollWhileVisible
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.StaleBanner
import com.vknxworks.proxctrl.ui.components.StatusDot
import com.vknxworks.proxctrl.ui.components.TagRow
import com.vknxworks.proxctrl.ui.components.UsageBar
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.components.formatCores
import com.vknxworks.proxctrl.ui.components.formatUptime
import com.vknxworks.proxctrl.ui.components.percent
import com.vknxworks.proxctrl.ui.components.usageColor
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STATUS_POLL_MS = 3_000L
private const val SLOW_POLL_MS = 30_000L
private const val RRD_POLL_MS = 60_000L
private const val NOTES_PRIV = "VM.Config.Options"

/**
 * A guest's page: status and live usage, power controls, IP addresses, OS, HA, start-at-boot and
 * order, notes (Markdown, editable), and CPU / memory / network / disk IO graphs.
 */
@Composable
fun GuestScreen(node: String, type: String, vmid: Int) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val ref = remember(node, type, vmid) { GuestRef(node, GuestType.of(type) ?: GuestType.QEMU, vmid) }
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val list by c.guests.state.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val listGuest = list.guests.firstOrNull { it.vmid == vmid && it.type == ref.type }

    var status by remember { mutableStateOf<GuestStatus?>(null) }
    var statusError by remember { mutableStateOf<PveError?>(null) }
    var statusAt by remember { mutableStateOf(0L) }
    var config by remember { mutableStateOf<GuestConfig?>(null) }
    var configVersion by remember { mutableStateOf(0) }
    var addresses by remember { mutableStateOf<List<GuestAddress>?>(null) }
    var addressesFailed by remember { mutableStateOf(false) }
    var osName by remember { mutableStateOf<String?>(null) }
    var timeframe by rememberSaveable { mutableStateOf(Timeframe.HOUR) }
    var rrd by remember { mutableStateOf<List<RrdPoint>>(emptyList()) }
    var pending by remember { mutableStateOf<PowerAction?>(null) }
    var editingNotes by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    PollWhileVisible(ref to server?.id, STATUS_POLL_MS) {
        val cl = client ?: return@PollWhileVisible false
        try {
            status = GuestApi.status(cl, ref)
            statusAt = System.currentTimeMillis()
            statusError = null
            true
        } catch (e: PveError) {
            statusError = e
            false
        }
    }
    LaunchedEffect(ref, server?.id, configVersion) {
        val cl = client ?: return@LaunchedEffect
        runCatching { GuestApi.config(cl, ref) }.onSuccess { config = it }
    }
    val running = status?.status == "running"
    // Addresses and OS change rarely: read them while running, every 30 s.
    PollWhileVisible(Triple(ref, server?.id, running), SLOW_POLL_MS) {
        val cl = client ?: return@PollWhileVisible false
        if (!running) {
            addresses = null
            return@PollWhileVisible true
        }
        try {
            addresses = GuestApi.addresses(cl, ref)
            addressesFailed = false
        } catch (e: PveError) {
            addressesFailed = true
        }
        if (ref.type == GuestType.QEMU && osName == null) osName = runCatching { GuestApi.osName(cl, ref) }.getOrNull()
        true
    }
    PollWhileVisible(Triple(ref, server?.id, timeframe), RRD_POLL_MS) {
        val cl = client ?: return@PollWhileVisible false
        runCatching { GuestApi.rrd(cl, ref, timeframe) }.onSuccess { rrd = it }.isSuccess
    }

    // The guest as the list knows it, with the live status and lock from this page.
    val guest: Guest? = listGuest?.let { g ->
        val st = status ?: return@let g
        g.copy(status = st.status, lock = st.lock.orEmpty(), uptime = st.uptime, name = st.name ?: g.name)
    }
    val title = guest?.let { "${it.vmid}  ${it.name}" } ?: vmid.toString()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(title)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp),
            ) {
                val err = statusError
                if (access.error != null) {
                    ConnectionBanner()
                } else if (err != null) {
                    if (err is PveError.Server && guest == null) {
                        Text(
                            stringResource(R.string.guest_not_found, server?.name.orEmpty()),
                            modifier = Modifier.padding(Gutter),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        StaleBanner(statusAt, err, server?.host.orEmpty(), server?.auth) {
                            scope.launch { client?.let { cl -> runCatching { status = GuestApi.status(cl, ref); statusError = null } } }
                        }
                    }
                }
                if (guest == null && status == null) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                Header(guest, status, ref, list.tagColors)

                if (guest != null && !guest.isTemplate && server?.readOnly == false) {
                    PowerRow(
                        guest = guest,
                        runState = status?.runState,
                        allowed = c.sessions.canWrite(ref.aclPath, PowerAction.PRIV),
                        onAction = { pending = it },
                    )
                }

                UsageGroup(guest, status, settings.diskWarnPercent)

                DetailsGroup(ref, status, config, addresses, addressesFailed, osName)

                NotesGroup(
                    notes = config?.description.orEmpty(),
                    canEdit = config != null && c.sessions.canWrite(ref.aclPath, NOTES_PRIV),
                    onEdit = { editingNotes = true },
                )

                GraphsGroup(rrd, timeframe, onTimeframe = { timeframe = it; rrd = emptyList() })
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp))
    }

    pending?.let { action ->
        val g = guest ?: return@let
        PowerConfirmDialog(
            action = action,
            name = "${g.vmid} ${g.name}",
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                val cl = client ?: return@PowerConfirmDialog
                scope.launch {
                    val result = cl.power(g, action)
                    val label = context.getString(action.label())
                    val e = result.error
                    snackbar.showSnackbar(
                        if (e == null) context.getString(R.string.power_started, g.name.ifBlank { g.vmid.toString() }, label)
                        else context.getString(R.string.power_failed, label) + ": " + errorMessage(context, e, server?.host.orEmpty(), server?.auth),
                    )
                    // Pick up the new state quickly instead of waiting for the next poll.
                    repeat(3) {
                        delay(1_500)
                        runCatching { status = GuestApi.status(cl, ref) }
                    }
                    c.guests.refresh()
                }
            },
        )
    }

    if (editingNotes) {
        NotesDialog(
            initial = config?.description.orEmpty(),
            onDismiss = { editingNotes = false },
            onSave = { text ->
                val cl = client ?: return@NotesDialog
                scope.launch {
                    try {
                        GuestApi.setNotes(cl, ref, text, config?.digest)
                        editingNotes = false
                        configVersion++
                        snackbar.showSnackbar(context.getString(R.string.notes_saved))
                    } catch (e: PveError) {
                        editingNotes = false
                        val conflict = (e as? PveError.Server)?.detail?.contains("modified", ignoreCase = true) == true
                        snackbar.showSnackbar(
                            if (conflict) context.getString(R.string.notes_conflict)
                            else errorMessage(context, e, server?.host.orEmpty(), server?.auth),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun Header(guest: Guest?, status: GuestStatus?, ref: GuestRef, tagColors: com.vknxworks.proxctrl.data.guests.TagColors) {
    val state = status?.runState ?: guest?.status ?: "unknown"
    val hibernated = guest?.lock == "suspended" && state == "stopped"
    val stateLabel = when {
        hibernated -> stringResource(R.string.status_hibernated)
        state == "running" -> stringResource(R.string.status_running)
        state == "stopped" -> stringResource(R.string.status_stopped)
        state == "paused" || state == "suspended" -> stringResource(R.string.status_paused)
        else -> stringResource(R.string.status_unknown)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (hibernated) "paused" else state, size = 12.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.guest_status_line, stateLabel, ref.node),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            if (state == "running" && status != null) {
                Text(stringResource(R.string.uptime_value, formatUptime(status.uptime)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            stringResource(if (ref.type == GuestType.LXC) R.string.lxc else R.string.qemu) +
                if (guest?.isTemplate == true) "  ·  " + stringResource(R.string.template) else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 22.dp),
        )
        if (!guest?.tags.isNullOrEmpty()) {
            Spacer(Modifier.height(8.dp))
            TagRow(guest!!.tags, tagColors)
        }
    }
}

@Composable
private fun PowerRow(guest: Guest, runState: String?, allowed: Boolean, onAction: (PowerAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PowerAction.BULK.forEach { action ->
                FilledTonalButton(
                    onClick = { onAction(action) },
                    enabled = allowed && action.appliesTo(guest, runState),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(action.icon(), null, Modifier.size(20.dp))
                        Text(stringResource(action.label()), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
            if (guest.type == GuestType.QEMU) {
                Box {
                    IconButton(onClick = { menu = true }, enabled = allowed) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf(PowerAction.SUSPEND, PowerAction.RESUME, PowerAction.HIBERNATE).forEach { action ->
                            DropdownMenuItem(
                                text = { Text(stringResource(action.label())) },
                                leadingIcon = { Icon(action.icon(), null) },
                                enabled = action.appliesTo(guest, runState),
                                onClick = { menu = false; onAction(action) },
                            )
                        }
                    }
                }
            }
        }
        if (!allowed) {
            Text(
                stringResource(R.string.not_allowed_action),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun UsageGroup(guest: Guest?, status: GuestStatus?, diskWarn: Int) {
    val st = status ?: return
    val running = st.status == "running"
    val accent = MaterialTheme.colorScheme.primary
    val diskUsed: Long = if (guest?.type == GuestType.LXC) st.disk else guest?.disk ?: 0L
    val diskTotal: Long = if (guest?.type == GuestType.LXC) st.maxDisk else guest?.maxDisk?.takeIf { it > 0 } ?: st.maxDisk
    val diskFraction = if (diskUsed > 0 && diskTotal > 0) diskUsed.toDouble() / diskTotal else null
    Group(stringResource(R.string.group_overview)) {
        UsageLine(
            stringResource(R.string.label_cpu),
            if (running) stringResource(R.string.row_cpu_detail, percent(st.cpu), formatCores(st.cpus)) else stringResource(R.string.no_value),
            if (running) st.cpu else null,
            accent,
        )
        val memFraction = if (running && st.maxMem > 0) st.mem.toDouble() / st.maxMem else null
        UsageLine(
            stringResource(R.string.label_mem),
            if (running) stringResource(R.string.row_mem_detail, formatBytes(st.mem), formatBytes(st.maxMem)) else formatBytes(st.maxMem),
            memFraction,
            accent,
        )
        UsageLine(
            stringResource(R.string.label_disk),
            if (diskFraction != null) stringResource(R.string.row_disk_detail, formatBytes(diskUsed), formatBytes(diskTotal))
            else if (guest?.type == GuestType.QEMU) stringResource(R.string.row_disk_unknown) else formatBytes(diskTotal),
            diskFraction,
            usageColor(diskFraction, diskWarn, accent),
        )
    }
}

@Composable
private fun UsageLine(label: String, value: String, fraction: Double?, color: androidx.compose.ui.graphics.Color) {
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
private fun DetailsGroup(
    ref: GuestRef,
    status: GuestStatus?,
    config: GuestConfig?,
    addresses: List<GuestAddress>?,
    addressesFailed: Boolean,
    osName: String?,
) {
    val running = status?.status == "running"
    Group(stringResource(R.string.group_details)) {
        val ipText = when {
            !running -> stringResource(R.string.ip_not_running)
            addresses == null && addressesFailed && ref.type == GuestType.QEMU -> stringResource(R.string.ip_needs_agent)
            addresses == null -> "…"
            addresses.isEmpty() -> stringResource(R.string.ip_none_running)
            else -> addresses.joinToString("\n") { a -> "${a.iface}  ${a.address}${a.prefix?.let { "/$it" }.orEmpty()}" }
        }
        GroupRow(stringResource(R.string.row_ip_addresses), ipText)
        val os = osName ?: config?.osType
        if (!os.isNullOrBlank()) GroupRow(stringResource(R.string.row_os), os)
        config?.hostname?.let { GroupRow(stringResource(R.string.row_hostname), it) }
        GroupRow(
            stringResource(R.string.row_ha),
            if (status?.haManaged == true) status.haState ?: stringResource(R.string.status_unknown) else stringResource(R.string.ha_not_managed),
        )
        if (config != null) {
            GroupRow(stringResource(R.string.row_onboot), stringResource(if (config.onBoot) R.string.yes else R.string.no))
            val order = listOfNotNull(
                config.startOrder?.let { stringResource(R.string.start_order_value, it.toString()) } ?: stringResource(R.string.start_order_any),
                config.startUpDelay?.let { stringResource(R.string.start_up_delay, it) },
                config.startDownDelay?.let { stringResource(R.string.start_down_delay, it) },
            ).joinToString("\n")
            GroupRow(stringResource(R.string.row_start_order), order)
        }
        status?.lock?.takeIf { it.isNotBlank() }?.let { GroupRow(stringResource(R.string.row_lock), it) }
    }
}

@Composable
private fun NotesGroup(notes: String, canEdit: Boolean, onEdit: () -> Unit) {
    Group(stringResource(R.string.group_notes)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (notes.isBlank()) {
                Text(stringResource(R.string.notes_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                MarkdownText(notes)
            }
            if (canEdit) {
                TextButton(onClick = onEdit, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Filled.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (notes.isBlank()) R.string.notes_add else R.string.notes_edit))
                }
            }
        }
    }
}

@Composable
private fun NotesDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notes_edit)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 6,
                maxLines = 14,
                supportingText = { Text(stringResource(R.string.notes_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { saving = true; onSave(text) }, enabled = !saving && text != initial) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PowerConfirmDialog(action: PowerAction, name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (title, body) = when (action) {
        PowerAction.START -> R.string.confirm_start_title to null
        PowerAction.SHUTDOWN -> R.string.confirm_shutdown_title to R.string.confirm_shutdown_body
        PowerAction.REBOOT -> R.string.confirm_reboot_title to R.string.confirm_reboot_body
        PowerAction.STOP -> R.string.confirm_stop_title to R.string.confirm_stop_body
        PowerAction.SUSPEND -> R.string.confirm_suspend_title to R.string.confirm_suspend_body
        PowerAction.RESUME -> R.string.confirm_resume_title to null
        PowerAction.HIBERNATE -> R.string.confirm_hibernate_title to R.string.confirm_hibernate_body
    }
    val danger = action == PowerAction.STOP
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(action.icon(), null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(title, name)) },
        text = if (body != null) {
            { Text(stringResource(body), color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            null
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(action.label()), color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun GraphsGroup(rrd: List<RrdPoint>, timeframe: Timeframe, onTimeframe: (Timeframe) -> Unit) {
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
            Text(stringResource(R.string.chart_no_data), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        } else {
            val times = rrd.map { it.time }
            fun col(key: String, scale: Double = 1.0) = rrd.map { p -> p.values[key]?.let { it * scale } }
            // "%s/s" in the user's language, filled with a byte size.
            val rateTemplate = stringResource(R.string.per_second, "%s")
            val rate: (Double) -> String = { v -> rateTemplate.replace("%s", formatBytes(v.toLong())) }
            val maxMem = rrd.mapNotNull { it.values["maxmem"] }.maxOrNull()

            ChartCard(stringResource(R.string.chart_cpu)) {
                LineChart(times, listOf(ChartSeries(stringResource(R.string.series_cpu), col("cpu", 100.0))), format = { "${it.toInt()}%" }, yMax = 100.0)
            }
            ChartCard(stringResource(R.string.chart_memory)) {
                LineChart(times, listOf(ChartSeries(stringResource(R.string.series_used), col("mem"))), format = { formatBytes(it.toLong()) }, yMax = maxMem)
            }
            ChartCard(stringResource(R.string.chart_network)) {
                LineChart(
                    times,
                    listOf(ChartSeries(stringResource(R.string.series_in), col("netin")), ChartSeries(stringResource(R.string.series_out), col("netout"))),
                    format = rate,
                )
            }
            ChartCard(stringResource(R.string.chart_disk_io)) {
                LineChart(
                    times,
                    listOf(ChartSeries(stringResource(R.string.series_read), col("diskread")), ChartSeries(stringResource(R.string.series_write), col("diskwrite"))),
                    format = rate,
                )
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
