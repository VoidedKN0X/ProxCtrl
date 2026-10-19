package com.vknxworks.proxctrl.ui.guests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.guests.GuestApi
import com.vknxworks.proxctrl.data.guests.GuestRef
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.guests.Snapshot
import com.vknxworks.proxctrl.data.guests.SnapshotApi
import com.vknxworks.proxctrl.data.guests.SnapshotNode
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.EmptyState
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.PillButton
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.TaskCard
import com.vknxworks.proxctrl.ui.components.TypedConfirmDialog
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.components.rememberTaskTracker
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A guest's snapshots as a tree, oldest at the top and "Now" where the guest is. Take, roll back
 * and delete; rolling back and deleting need the guest ID typed in.
 */
@Composable
fun SnapshotsScreen(node: String, type: String, vmid: Int, name: String) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val ref = remember(node, type, vmid) { GuestRef(node, GuestType.of(type) ?: GuestType.QEMU, vmid) }
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val list by c.guests.state.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val guest = list.guests.firstOrNull { it.vmid == vmid && it.type == ref.type }
    val tracker = rememberTaskTracker()

    var snapshots by remember { mutableStateOf<List<Snapshot>?>(null) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var taking by remember { mutableStateOf(false) }
    var rollingBack by remember { mutableStateOf<Snapshot?>(null) }
    var deleting by remember { mutableStateOf<Snapshot?>(null) }

    LaunchedEffect(ref, server?.id, reload) {
        val cl = client ?: return@LaunchedEffect
        try {
            snapshots = SnapshotApi.list(cl, ref)
            error = null
        } catch (e: PveError) {
            error = e
        }
        refreshing = false
    }
    fun afterTask(ok: Boolean) {
        reload++
        scope.launch { c.guests.refresh() }
    }

    val canWrite = server?.readOnly == false && guest?.isTemplate != true
    val canSnapshot = canWrite && c.sessions.canWrite(ref.aclPath, SnapshotApi.PRIV)
    val canRollback = canWrite && (canSnapshot || c.sessions.canWrite(ref.aclPath, SnapshotApi.PRIV_ROLLBACK))
    val running = guest?.isRunning == true
    val title = listOf(vmid.toString(), name.ifBlank { guest?.name.orEmpty() }).filter { it.isNotBlank() }.joinToString("  ")

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.snapshots_title, title))
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reload++ },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                if (access.error != null) ConnectionBanner()
                TaskCard(tracker)
                if (server?.readOnly == false && guest?.isTemplate != true) {
                    PillButton(
                        text = stringResource(R.string.snapshot_take),
                        onClick = { taking = true },
                        icon = Icons.Filled.AddAPhoto,
                        enabled = canSnapshot && !tracker.busy && guest?.lock.isNullOrEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
                    )
                    if (!canSnapshot) {
                        Text(
                            stringResource(R.string.not_allowed_action),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Gutter + 4.dp),
                        )
                    }
                }
                val err = error
                val snaps = snapshots
                when {
                    snaps == null && err == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    snaps == null && err != null -> Text(
                        errorMessage(context, err, server?.host.orEmpty(), server?.auth),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(Gutter),
                    )
                    snaps != null && snaps.none { !it.isCurrent } -> EmptyState(
                        icon = Icons.Filled.PhotoCamera,
                        title = stringResource(R.string.snapshots_empty),
                        body = stringResource(R.string.snapshots_empty_hint),
                        modifier = Modifier.heightIn(min = 320.dp),
                    )
                    snaps != null -> {
                        Group(footer = stringResource(R.string.snapshots_footer)) {
                            SnapshotApi.tree(snaps).forEach { n ->
                                SnapshotRow(
                                    node = n,
                                    canRollback = canRollback && !tracker.busy,
                                    canDelete = canSnapshot && !tracker.busy,
                                    onRollback = { rollingBack = n.snapshot },
                                    onDelete = { deleting = n.snapshot },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (taking) {
        TakeSnapshotDialog(
            isVm = ref.type == GuestType.QEMU,
            running = running,
            existing = snapshots.orEmpty().map { it.name }.toSet(),
            onDismiss = { taking = false },
            onTake = { snapName, description, withRam ->
                taking = false
                val cl = client ?: return@TakeSnapshotDialog
                tracker.run(context.getString(R.string.snapshot_task_take, snapName), cl, server?.host.orEmpty(), server?.auth, ::afterTask) {
                    SnapshotApi.take(cl, ref, snapName, description, withRam)
                }
            },
        )
    }

    rollingBack?.let { snap ->
        var start by remember(snap) { mutableStateOf(false) }
        TypedConfirmDialog(
            title = stringResource(R.string.snapshot_rollback_title, snap.name),
            body = buildString {
                append(context.getString(R.string.snapshot_rollback_body))
                if (running) append("\n\n").append(context.getString(R.string.snapshot_rollback_running))
                if (snap.withRam) append("\n\n").append(context.getString(R.string.snapshot_rollback_ram))
            },
            expected = vmid.toString(),
            confirmLabel = stringResource(R.string.snapshot_rollback),
            icon = Icons.Filled.History,
            extra = if (!snap.withRam) {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.start_afterwards), modifier = Modifier.weight(1f))
                        Switch(checked = start, onCheckedChange = { start = it })
                    }
                }
            } else {
                null
            },
            onDismiss = { rollingBack = null },
            onConfirm = {
                rollingBack = null
                val cl = client ?: return@TypedConfirmDialog
                tracker.run(context.getString(R.string.snapshot_task_rollback, snap.name), cl, server?.host.orEmpty(), server?.auth, ::afterTask) {
                    SnapshotApi.rollback(cl, ref, snap.name, start && !snap.withRam)
                }
            },
        )
    }

    deleting?.let { snap ->
        TypedConfirmDialog(
            title = stringResource(R.string.snapshot_delete_title, snap.name),
            body = stringResource(R.string.snapshot_delete_body),
            expected = vmid.toString(),
            confirmLabel = stringResource(R.string.delete),
            icon = Icons.Filled.Delete,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                val cl = client ?: return@TypedConfirmDialog
                tracker.run(context.getString(R.string.snapshot_task_delete, snap.name), cl, server?.host.orEmpty(), server?.auth, ::afterTask) {
                    SnapshotApi.delete(cl, ref, snap.name)
                }
            },
        )
    }
}

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

internal fun formatDateTime(epochSeconds: Long): String =
    dateFormat.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))

@Composable
private fun SnapshotRow(
    node: SnapshotNode,
    canRollback: Boolean,
    canDelete: Boolean,
    onRollback: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = node.snapshot
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Branch lines: one rail per level, so children sit visibly under their parent.
        repeat(node.depth) {
            Box(Modifier.width(16.dp).height(48.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
        Icon(
            if (s.isCurrent) Icons.Filled.PlayArrow else Icons.Filled.PhotoCamera,
            null,
            Modifier.size(20.dp),
            tint = if (s.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                if (s.isCurrent) stringResource(R.string.snapshot_now) else s.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (s.isCurrent) FontWeight.SemiBold else null,
                color = if (s.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            val sub = if (s.isCurrent) {
                stringResource(R.string.snapshot_now_hint)
            } else {
                listOfNotNull(s.time?.let(::formatDateTime), s.description.takeIf { it.isNotBlank() }).joinToString("\n")
            }
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
            }
            if (s.withRam) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.snapshot_with_ram)) },
                    leadingIcon = { Icon(Icons.Filled.Memory, null, Modifier.size(16.dp)) },
                    modifier = Modifier.height(28.dp).padding(top = 4.dp),
                )
            }
        }
        if (!s.isCurrent && (canRollback || canDelete)) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.snapshot_rollback)) },
                        leadingIcon = { Icon(Icons.Filled.History, null) },
                        enabled = canRollback,
                        onClick = { menu = false; onRollback() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        enabled = canDelete,
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
private fun TakeSnapshotDialog(
    isVm: Boolean,
    running: Boolean,
    existing: Set<String>,
    onDismiss: () -> Unit,
    onTake: (name: String, description: String, withRam: Boolean) -> Unit,
) {
    val suggested = remember {
        "snap_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").format(java.time.LocalDateTime.now())
    }
    var name by remember { mutableStateOf(suggested) }
    var description by remember { mutableStateOf("") }
    var withRam by remember { mutableStateOf(false) }
    val valid = SnapshotApi.isValidName(name)
    val taken = name in existing
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.AddAPhoto, null) },
        title = { Text(stringResource(R.string.snapshot_take)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    singleLine = true,
                    label = { Text(stringResource(R.string.snapshot_name)) },
                    isError = name.isNotEmpty() && (!valid || taken),
                    supportingText = {
                        Text(stringResource(if (taken) R.string.snapshot_name_taken else R.string.snapshot_name_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.snapshot_description)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isVm && running) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.snapshot_include_ram))
                            Text(
                                stringResource(R.string.snapshot_include_ram_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = withRam, onCheckedChange = { withRam = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onTake(name, description, withRam && isVm && running) }, enabled = valid && !taken) {
                Text(stringResource(R.string.snapshot_take_short))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
