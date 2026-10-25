package com.vknxworks.proxctrl.ui.guests

import com.vknxworks.proxctrl.ui.components.CtrlDialog
import com.vknxworks.proxctrl.ui.components.CtrlSwitch
import com.vknxworks.proxctrl.ui.components.CtrlTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Surface
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
import com.vknxworks.proxctrl.data.guests.Backup
import com.vknxworks.proxctrl.data.guests.BackupApi
import com.vknxworks.proxctrl.data.guests.BackupList
import com.vknxworks.proxctrl.data.guests.BackupMode
import com.vknxworks.proxctrl.data.guests.Compression
import com.vknxworks.proxctrl.data.guests.GuestApi
import com.vknxworks.proxctrl.data.guests.GuestConfig
import com.vknxworks.proxctrl.data.guests.GuestRef
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.guests.StorageChoice
import com.vknxworks.proxctrl.ui.components.ConfirmDialog
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.EmptyState
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.PillButton
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.TaskCard
import com.vknxworks.proxctrl.ui.components.TypedConfirmDialog
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.components.rememberTaskTracker
import com.vknxworks.proxctrl.ui.components.relativeTime
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

/**
 * A guest's backups on every backup storage of its node, newest first. Back up now, restore over the
 * guest (big warning, guest ID typed in), and delete.
 */
@Composable
fun BackupsScreen(node: String, type: String, vmid: Int, name: String) {
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

    var backups by remember { mutableStateOf<BackupList?>(null) }
    var config by remember { mutableStateOf<GuestConfig?>(null) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var backingUp by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<Backup?>(null) }
    var deleting by remember { mutableStateOf<Backup?>(null) }

    LaunchedEffect(ref, server?.id, reload) {
        val cl = client ?: return@LaunchedEffect
        try {
            backups = BackupApi.list(cl, ref)
            error = null
        } catch (e: PveError) {
            error = e
        }
        runCatching { GuestApi.config(cl, ref) }.onSuccess { config = it }
        refreshing = false
    }
    fun afterTask(ok: Boolean) {
        reload++
        scope.launch { c.guests.refresh() }
    }

    val writable = server?.readOnly == false
    val canBackup = writable && c.sessions.canWrite(ref.aclPath, BackupApi.PRIV)
    val title = listOf(vmid.toString(), name.ifBlank { guest?.name.orEmpty() }).filter { it.isNotBlank() }.joinToString("  ")

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.backups_title, title))
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reload++ },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                if (access.error != null) ConnectionBanner()
                TaskCard(tracker)
                if (writable) {
                    PillButton(
                        text = stringResource(R.string.backup_now),
                        onClick = { backingUp = true },
                        icon = Icons.Outlined.Backup,
                        enabled = canBackup && !tracker.busy && guest?.lock.isNullOrEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
                    )
                    if (!canBackup) {
                        Text(
                            stringResource(R.string.not_allowed_action),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Gutter + 4.dp),
                        )
                    }
                }
                val err = error
                val data = backups
                data?.failures?.forEach { f ->
                    Text(
                        stringResource(R.string.backups_storage_failed, f.storage, errorMessage(context, f.error, server?.host.orEmpty(), server?.auth)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 4.dp),
                    )
                }
                when {
                    data == null && err == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    data == null && err != null -> Text(
                        errorMessage(context, err, server?.host.orEmpty(), server?.auth),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(Gutter),
                    )
                    data != null && data.backups.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.Inventory2,
                        title = stringResource(R.string.backups_empty),
                        body = stringResource(R.string.backups_empty_hint),
                        modifier = Modifier.heightIn(min = 320.dp),
                    )
                    data != null -> Group(footer = stringResource(R.string.backups_footer)) {
                        data.backups.forEach { b ->
                            val canDelete = writable && !b.isProtected &&
                                c.sessions.canWrite(ref.aclPath, BackupApi.PRIV) &&
                                c.sessions.canWrite("/storage/${b.storage}", BackupApi.STORAGE_PRIV)
                            BackupRow(
                                backup = b,
                                canRestore = canBackup && !tracker.busy,
                                canDelete = canDelete && !tracker.busy,
                                showMenu = writable,
                                onRestore = { restoring = b },
                                onDelete = { deleting = b },
                            )
                        }
                    }
                }
            }
        }
    }

    if (backingUp) {
        BackupNowDialog(
            node = ref.node,
            canUse = { s -> c.sessions.canWrite("/storage/${s.id}", BackupApi.STORAGE_PRIV) },
            running = guest?.isRunning == true,
            onDismiss = { backingUp = false },
            onStart = { storage, mode, compression, notes, protect ->
                backingUp = false
                val cl = client ?: return@BackupNowDialog
                tracker.run(context.getString(R.string.backup_task, storage.id), cl, server?.host.orEmpty(), server?.auth, ::afterTask) {
                    BackupApi.backupNow(cl, ref, storage, mode, compression, notes, protect)
                }
            },
        )
    }

    restoring?.let { b ->
        RestoreDialog(
            ref = ref,
            backup = b,
            running = guest?.isRunning == true || guest?.status == "paused",
            guestProtected = config?.isProtected == true,
            defaultStorage = if (ref.type == GuestType.LXC) BackupApi.rootfsStorage(config?.rootfs) else null,
            canUse = { s -> c.sessions.canWrite("/storage/${s.id}", BackupApi.STORAGE_PRIV) },
            onDismiss = { restoring = null },
            onRestore = { storage, start ->
                restoring = null
                val cl = client ?: return@RestoreDialog
                tracker.run(context.getString(R.string.restore_task, formatDateTime(b.time)), cl, server?.host.orEmpty(), server?.auth, ::afterTask) {
                    BackupApi.restore(cl, ref, b, storage, start)
                }
            },
        )
    }

    deleting?.let { b ->
        ConfirmDialog(
            title = stringResource(R.string.backup_delete_title),
            body = stringResource(R.string.backup_delete_body, formatDateTime(b.time), b.storage, formatBytes(b.size)),
            confirmLabel = stringResource(R.string.delete),
            icon = Icons.Outlined.Delete,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                val cl = client ?: return@ConfirmDialog
                tracker.run(context.getString(R.string.backup_delete_task, formatDateTime(b.time)), cl, server?.host.orEmpty(), server?.auth, ::afterTask) {
                    BackupApi.delete(cl, ref.node, b)
                }
            },
        )
    }
}

@Composable
private fun BackupRow(
    backup: Backup,
    canRestore: Boolean,
    canDelete: Boolean,
    showMenu: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Inventory2, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDateTime(backup.time), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (backup.isProtected) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.Lock, stringResource(R.string.backup_protected), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
                when (backup.verification) {
                    "ok" -> {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.CheckCircle, stringResource(R.string.backup_verified), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    "failed" -> {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.Error, stringResource(R.string.backup_verify_failed), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text(
                stringResource(R.string.backup_row_detail, relativeTime(backup.time * 1000), backup.storage, formatBytes(backup.size)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (backup.notes.isNotBlank()) {
                Text(backup.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
            if (backup.verification == "failed") {
                Text(stringResource(R.string.backup_verify_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (showMenu) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_actions)) }
                DropdownMenu(expanded = menu && !com.vknxworks.proxctrl.ui.components.LocalAppCovered.current, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.restore)) },
                        leadingIcon = { Icon(Icons.Outlined.Restore, null) },
                        enabled = canRestore,
                        onClick = { menu = false; onRestore() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (backup.isProtected) R.string.backup_delete_protected else R.string.delete)) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        enabled = canDelete,
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** Storage picker for the backup and restore dialogs. */
@Composable
private fun StoragePicker(
    label: String,
    storages: List<StorageChoice>?,
    selected: String?,
    onSelect: (String?) -> Unit,
    /** Shown as the first choice when picking no storage is allowed (VM restore: keep the backup's). */
    noneLabel: String? = null,
    error: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val text = when {
        storages == null -> stringResource(R.string.loading)
        selected == null -> noneLabel ?: stringResource(R.string.storage_pick)
        else -> storages.firstOrNull { it.id == selected }?.let { s ->
            if (s.total > 0) stringResource(R.string.storage_choice, s.id, formatBytes(s.avail)) else s.id
        } ?: selected
    }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (storages != null) open = it }) {
        CtrlTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open && !com.vknxworks.proxctrl.ui.components.LocalAppCovered.current, onDismissRequest = { open = false }) {
            if (noneLabel != null) {
                DropdownMenuItem(text = { Text(noneLabel) }, onClick = { onSelect(null); open = false })
            }
            storages.orEmpty().forEach { s ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(s.id)
                            Text(
                                if (s.total > 0) stringResource(R.string.storage_choice_detail, s.type, formatBytes(s.avail)) else s.type,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { onSelect(s.id); open = false },
                )
            }
        }
    }
}

@Composable
private fun BackupNowDialog(
    node: String,
    canUse: (StorageChoice) -> Boolean,
    running: Boolean,
    onDismiss: () -> Unit,
    onStart: (StorageChoice, BackupMode, Compression, String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val client = context.container.sessions.active.collectAsStateWithLifecycle().value?.client
    var storages by remember { mutableStateOf<List<StorageChoice>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var storageId by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(BackupMode.SNAPSHOT) }
    var compression by remember { mutableStateOf(Compression.ZSTD) }
    var notes by remember { mutableStateOf("{{guestname}}") }
    var protect by remember { mutableStateOf(false) }

    LaunchedEffect(node) {
        val cl = client ?: return@LaunchedEffect
        try {
            val all = BackupApi.storages(cl, node, "backup").filter { it.active && canUse(it) }
            storages = all
            // Prefer Proxmox Backup Server, then the largest storage.
            storageId = (all.firstOrNull { it.isPbs } ?: all.maxByOrNull { it.avail })?.id
            if (all.isEmpty()) loadError = context.getString(R.string.backup_no_storage)
        } catch (e: PveError) {
            storages = emptyList()
            loadError = errorMessage(context, e, "", null)
        }
    }
    val storage = storages?.firstOrNull { it.id == storageId }

    CtrlDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Backup, null) },
        title = { Text(stringResource(R.string.backup_now)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StoragePicker(stringResource(R.string.backup_storage), storages, storageId, { storageId = it }, error = loadError)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.backup_mode), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        BackupMode.SNAPSHOT to R.string.backup_mode_snapshot,
                        BackupMode.SUSPEND to R.string.backup_mode_suspend,
                        BackupMode.STOP to R.string.backup_mode_stop,
                    ).forEach { (m, label) -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(stringResource(label)) }) }
                }
                Text(
                    stringResource(
                        when (mode) {
                            BackupMode.SNAPSHOT -> R.string.backup_mode_snapshot_hint
                            BackupMode.SUSPEND -> R.string.backup_mode_suspend_hint
                            BackupMode.STOP -> R.string.backup_mode_stop_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mode == BackupMode.STOP && running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (storage?.isPbs != true) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.backup_compression), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            Compression.ZSTD to R.string.compression_zstd,
                            Compression.LZO to R.string.compression_lzo,
                            Compression.GZIP to R.string.compression_gzip,
                            Compression.NONE to R.string.compression_none,
                        ).forEach { (cmp, label) -> FilterChip(selected = compression == cmp, onClick = { compression = cmp }, label = { Text(stringResource(label)) }) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                CtrlTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.backup_notes)) },
                    supportingText = { Text(stringResource(R.string.backup_notes_hint)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.backup_protect))
                        Text(stringResource(R.string.backup_protect_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CtrlSwitch(checked = protect, onCheckedChange = { protect = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { storage?.let { onStart(it, mode, compression, notes, protect) } },
                enabled = storage != null,
            ) { Text(stringResource(R.string.backup_start)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RestoreDialog(
    ref: GuestRef,
    backup: Backup,
    running: Boolean,
    guestProtected: Boolean,
    defaultStorage: String?,
    canUse: (StorageChoice) -> Boolean,
    onDismiss: () -> Unit,
    onRestore: (storage: String?, start: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val client = context.container.sessions.active.collectAsStateWithLifecycle().value?.client
    val isLxc = ref.type == GuestType.LXC
    var storages by remember { mutableStateOf<List<StorageChoice>?>(null) }
    var storageId by remember { mutableStateOf<String?>(null) }
    var start by remember { mutableStateOf(false) }

    LaunchedEffect(ref) {
        val cl = client ?: return@LaunchedEffect
        val all = runCatching { BackupApi.storages(cl, ref.node, if (isLxc) "rootdir" else "images") }.getOrDefault(emptyList())
            .filter { it.active && canUse(it) }
        storages = all
        // Containers must name a storage (Proxmox would pick "local" otherwise): keep the current one.
        if (isLxc) storageId = all.firstOrNull { it.id == defaultStorage }?.id ?: all.firstOrNull()?.id
    }

    // A running or protected guest can't be restored over: say so instead of asking for confirmation.
    if (running || guestProtected) {
        CtrlDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.Restore, null) },
            title = { Text(stringResource(R.string.restore_title)) },
            text = { Text(stringResource(if (running) R.string.restore_needs_stopped else R.string.restore_protected)) },
            confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.done)) } },
        )
        return
    }

    TypedConfirmDialog(
        title = stringResource(R.string.restore_title),
        body = stringResource(R.string.restore_body, ref.vmid.toString(), formatDateTime(backup.time), backup.storage),
        expected = ref.vmid.toString(),
        confirmLabel = stringResource(R.string.restore),
        icon = Icons.Outlined.Restore,
        extra = {
            Column {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(
                        stringResource(R.string.restore_warning),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                StoragePicker(
                    label = stringResource(R.string.restore_storage),
                    storages = storages,
                    selected = storageId,
                    onSelect = { storageId = it },
                    noneLabel = if (isLxc) null else stringResource(R.string.restore_storage_from_backup),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.start_afterwards), modifier = Modifier.weight(1f))
                    CtrlSwitch(checked = start, onCheckedChange = { start = it })
                }
            }
        },
        onDismiss = onDismiss,
        onConfirm = {
            if (isLxc && storageId == null) return@TypedConfirmDialog
            onRestore(storageId, start)
        },
    )
}
