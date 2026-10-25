package com.vknxworks.proxctrl.ui.guests

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.guests.CreateApi
import com.vknxworks.proxctrl.data.guests.GuestDisk
import com.vknxworks.proxctrl.data.guests.GuestRef
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.storage.StorageInfo
import com.vknxworks.proxctrl.ui.components.ConfirmDialog
import com.vknxworks.proxctrl.ui.components.CtrlDialog
import com.vknxworks.proxctrl.ui.components.CtrlSwitch
import com.vknxworks.proxctrl.ui.components.CtrlTextField
import com.vknxworks.proxctrl.ui.components.formatBytes

/** What the move-disk dialog chose. */
data class MoveChoice(val storage: String, val format: String?, val deleteSource: Boolean, val bwLimitMiB: Int?)

/** Whether [disk] can be moved to another storage (bind mounts and pass-through disks can't). */
internal fun GuestDisk.movable(): Boolean = storage != null

/**
 * Move a disk or volume to another storage. Only storages on the guest's node that can hold the
 * content (VM images, or container directories) and that the sign-in may allocate on are offered.
 * A second dialog confirms before anything is sent.
 */
@Composable
internal fun MoveDiskDialog(
    ref: GuestRef,
    disk: GuestDisk,
    hasSnapshots: Boolean,
    onDismiss: () -> Unit,
    onMove: (MoveChoice) -> Unit,
) {
    val context = LocalContext.current
    val c = context.container
    val client = c.sessions.active.value?.client
    val content = if (ref.type == GuestType.LXC) "rootdir" else "images"
    var storages by remember { mutableStateOf<List<StorageInfo>?>(null) }
    var target by remember { mutableStateOf<String?>(null) }
    var format by remember { mutableStateOf<String?>(null) }
    var deleteSource by remember { mutableStateOf(false) }
    var limit by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }

    LaunchedEffect(ref, disk.key) {
        val cl = client ?: return@LaunchedEffect
        storages = runCatching { CreateApi.storagesFor(cl, ref.node, content) }.getOrDefault(emptyList())
            .filter { it.id != disk.storage }
    }
    val targetInfo = storages?.firstOrNull { it.id == target }
    val fileTarget = ref.type == GuestType.QEMU && targetInfo?.let(CreateApi::isFileStorage) == true
    val limitVal = limit.toIntOrNull()?.takeIf { it > 0 }
    val limitOk = limit.isBlank() || limitVal != null
    val deleteBlocked = deleteSource && hasSnapshots

    CtrlDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.SwapHoriz, null) },
        title = { Text(stringResource(R.string.move_title, disk.key)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.move_from, disk.volume, disk.size?.let(::formatBytes) ?: "?"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.move_target), style = MaterialTheme.typography.labelLarge)
                val list = storages
                when {
                    list == null -> Text(stringResource(R.string.create_loading), style = MaterialTheme.typography.bodySmall)
                    list.isEmpty() -> Text(stringResource(R.string.move_no_storage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { s ->
                            val allowed = c.sessions.canWrite("/storage/${s.id}", CreateApi.PRIV_SPACE)
                            FilterChip(
                                selected = target == s.id,
                                onClick = { target = s.id; if (!(ref.type == GuestType.QEMU && CreateApi.isFileStorage(s))) format = null },
                                enabled = allowed,
                                label = { Text(stringResource(R.string.create_storage_free, s.id, formatBytes(s.avail))) },
                            )
                        }
                    }
                }
                if (list?.any { !c.sessions.canWrite("/storage/${it.id}", CreateApi.PRIV_SPACE) } == true) {
                    Text(stringResource(R.string.create_storage_not_allowed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (fileTarget) {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.create_disk_format), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = format == null, onClick = { format = null }, label = { Text(stringResource(R.string.move_format_keep)) })
                        listOf("qcow2", "raw", "vmdk").forEach { f ->
                            FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f) })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.move_delete_source))
                        Text(
                            stringResource(if (deleteBlocked) R.string.move_delete_snapshots else R.string.move_delete_source_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (deleteBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CtrlSwitch(checked = deleteSource, onCheckedChange = { deleteSource = it })
                }
                Spacer(Modifier.height(8.dp))
                CtrlTextField(
                    value = limit,
                    onValueChange = { limit = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.move_bwlimit)) },
                    supportingText = { Text(stringResource(R.string.move_bwlimit_hint)) },
                    isError = !limitOk,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ref.type == GuestType.QEMU) {
                    Text(stringResource(R.string.move_vm_running_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirming = true }, enabled = target != null && limitOk && !deleteBlocked) {
                Text(stringResource(R.string.move_button))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    val t = target
    if (confirming && t != null) {
        ConfirmDialog(
            title = stringResource(R.string.move_confirm_title, disk.key, t),
            body = stringResource(if (deleteSource) R.string.move_confirm_body_delete else R.string.move_confirm_body_keep, disk.volume),
            confirmLabel = stringResource(R.string.move_button),
            danger = deleteSource,
            icon = Icons.Outlined.SwapHoriz,
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                onMove(MoveChoice(t, if (fileTarget) format else null, deleteSource, limitVal))
            },
        )
    }
}
