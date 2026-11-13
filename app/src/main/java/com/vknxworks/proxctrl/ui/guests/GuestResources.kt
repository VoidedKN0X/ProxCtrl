package com.vknxworks.proxctrl.ui.guests

import com.vknxworks.proxctrl.ui.components.CtrlDialog
import com.vknxworks.proxctrl.ui.components.CtrlSwitch
import com.vknxworks.proxctrl.ui.components.CtrlTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inventory
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.guests.BackupApi
import com.vknxworks.proxctrl.data.guests.GuestDisk
import com.vknxworks.proxctrl.data.guests.GuestRef
import com.vknxworks.proxctrl.data.guests.GuestResources
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.guests.ResourcesApi
import com.vknxworks.proxctrl.data.guests.StorageChoice
import com.vknxworks.proxctrl.data.guests.TagColors
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.TagChip
import com.vknxworks.proxctrl.ui.components.TypedConfirmDialog
import com.vknxworks.proxctrl.ui.components.formatBytes

private const val MIB = 1024L * 1024L

/** "4 GiB" or "512 MiB" from a MiB value. */
internal fun formatMiB(mib: Long): String = formatBytes(mib * MIB)

/** Cores, memory, disks and network devices, with edit, tag and grow-disk actions where allowed. */
@Composable
internal fun ResourcesGroup(
    type: GuestType,
    res: GuestResources,
    canEditHardware: Boolean,
    canEditTags: Boolean,
    canResize: Boolean,
    onEdit: () -> Unit,
    onTags: () -> Unit,
    onResize: (GuestDisk) -> Unit,
    canMove: Boolean = false,
    onMove: (GuestDisk) -> Unit = {},
    /** VMs: running now. Proxmox can't move a running VM's TPM state, so that disk gets no Move button then. */
    running: Boolean = false,
) {
    Group(stringResource(R.string.group_resources)) {
        val cpu = if (type == GuestType.QEMU && res.sockets > 1) {
            stringResource(R.string.res_cpu_sockets, res.sockets, res.cores, res.sockets * res.cores)
        } else {
            pluralStringRes(R.plurals.res_cores, res.cores)
        }
        GroupRow(
            title = stringResource(R.string.label_cpu),
            subtitle = listOfNotNull(cpu, res.cpuType?.let { stringResource(R.string.res_cpu_type, it) }).joinToString("\n"),
        )
        val memLines = buildList {
            add(formatMiB(res.memoryMiB))
            res.balloonMiB?.let { b -> add(if (b == 0L) stringResource(R.string.res_balloon_off) else stringResource(R.string.res_balloon_min, formatMiB(b))) }
            res.swapMiB?.let { add(stringResource(R.string.res_swap, formatMiB(it))) }
        }
        GroupRow(stringResource(R.string.label_mem), memLines.joinToString("\n"))
        if (canEditHardware) {
            TextButton(onClick = onEdit, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.res_edit_cpu_memory))
            }
        }
        res.disks.forEach { d ->
            GroupRow(
                title = diskTitle(d),
                subtitle = listOfNotNull(d.volume.takeIf { it.isNotBlank() }, d.size?.let(::formatBytes)).joinToString("\n"),
                icon = Icons.Outlined.Inventory,
                trailing = if ((canResize && d.resizable) || (canMove && d.movable() && !(running && d.key.startsWith("tpmstate")))) {
                    {
                        Row {
                            if (canMove && d.movable() && !(running && d.key.startsWith("tpmstate"))) TextButton(onClick = { onMove(d) }) { Text(stringResource(R.string.move_button)) }
                            if (canResize && d.resizable) TextButton(onClick = { onResize(d) }) { Text(stringResource(R.string.res_grow)) }
                        }
                    }
                } else {
                    null
                },
            )
        }
        res.nics.forEach { n ->
            GroupRow(
                title = n.name?.let { "${n.key} · $it" } ?: n.key,
                subtitle = listOfNotNull(
                    listOfNotNull(n.model, n.mac).joinToString(" ").ifBlank { null },
                    n.bridge?.let { b -> stringResource(R.string.res_bridge, b) + (n.vlan?.let { "  ·  " + stringResource(R.string.res_vlan, it) } ?: "") },
                    n.ip,
                    if (n.firewall) stringResource(R.string.res_firewall_on) else null,
                ).joinToString("\n"),
            )
        }
        if (canEditTags) {
            TextButton(onClick = onTags, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (res.tags.isEmpty()) R.string.res_tags_add else R.string.res_tags_edit))
            }
        }
    }
}

@Composable
private fun pluralStringRes(id: Int, count: Int): String =
    androidx.compose.ui.res.pluralStringResource(id, count, count)

@Composable
private fun diskTitle(d: GuestDisk): String = when {
    d.key == "rootfs" -> stringResource(R.string.res_rootfs)
    d.mountPoint != null -> "${d.key} · ${d.mountPoint}"
    else -> d.key
}

/** Cores and memory (and swap for containers). */
@Composable
internal fun EditResourcesDialog(
    type: GuestType,
    res: GuestResources,
    running: Boolean,
    canCpu: Boolean,
    canMemory: Boolean,
    onDismiss: () -> Unit,
    onSave: (cores: Int, memoryMiB: Long, swapMiB: Long?) -> Unit,
) {
    var cores by remember { mutableStateOf(res.cores.toString()) }
    var memory by remember { mutableStateOf(res.memoryMiB.toString()) }
    var swap by remember { mutableStateOf(res.swapMiB?.toString().orEmpty()) }
    val c = cores.toIntOrNull()
    val m = memory.toLongOrNull()
    val s = if (type == GuestType.LXC) swap.toLongOrNull() else null
    val coresOk = c != null && c in 1..512
    val memOk = m != null && m >= 16
    val swapOk = type != GuestType.LXC || (s != null && s >= 0)
    val changed = c != res.cores || m != res.memoryMiB || (type == GuestType.LXC && s != res.swapMiB)
    CtrlDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.res_edit_cpu_memory)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CtrlTextField(
                    value = cores,
                    onValueChange = { cores = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(if (type == GuestType.QEMU && res.sockets > 1) R.string.res_cores_per_socket else R.string.res_cores_label)) },
                    enabled = canCpu,
                    isError = !coresOk,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                CtrlTextField(
                    value = memory,
                    onValueChange = { memory = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.res_memory_mib)) },
                    supportingText = { m?.let { Text(formatMiB(it)) } },
                    enabled = canMemory,
                    isError = !memOk,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1024L, 2048L, 4096L, 8192L, 16384L).forEach { preset ->
                        FilterChip(
                            selected = m == preset,
                            onClick = { memory = preset.toString() },
                            enabled = canMemory,
                            label = { Text(formatMiB(preset)) },
                        )
                    }
                }
                if (type == GuestType.LXC) {
                    Spacer(Modifier.height(8.dp))
                    CtrlTextField(
                        value = swap,
                        onValueChange = { swap = it.filter(Char::isDigit).take(8) },
                        label = { Text(stringResource(R.string.res_swap_mib)) },
                        supportingText = { s?.let { Text(formatMiB(it)) } },
                        enabled = canMemory,
                        isError = !swapOk,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (running && type == GuestType.QEMU) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.res_vm_pending_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(c ?: res.cores, m ?: res.memoryMiB, s) },
                enabled = coresOk && memOk && swapOk && changed,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Add and remove tags; colours follow the server's tag colours. */
@Composable
internal fun TagsDialog(initial: List<String>, colors: TagColors, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var tags by remember { mutableStateOf(initial) }
    var draft by remember { mutableStateOf("") }
    val draftOk = draft.isBlank() || ResourcesApi.TAG.matches(draft.trim())
    fun addDraft() {
        val t = draft.trim().lowercase()
        if (t.isNotEmpty() && ResourcesApi.TAG.matches(t) && t !in tags) tags = tags + t
        draft = ""
    }
    CtrlDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.res_tags_title)) },
        text = {
            Column {
                if (tags.isEmpty()) {
                    Text(stringResource(R.string.res_tags_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { t ->
                            InputChip(
                                selected = false,
                                onClick = { tags = tags - t },
                                label = { TagChip(t, colors) },
                                trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.res_tag_remove), Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CtrlTextField(
                        value = draft,
                        onValueChange = { draft = it.replace(" ", "").take(64) },
                        label = { Text(stringResource(R.string.res_tag_new)) },
                        isError = !draftOk,
                        supportingText = { Text(stringResource(R.string.res_tag_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.IconButton(onClick = ::addDraft, enabled = draft.isNotBlank() && draftOk) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.res_tag_add))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { addDraft(); onSave(tags) }, enabled = draftOk) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Grow a disk. Never shrinks; asks for the guest ID because a grown disk can't be made smaller again. */
@Composable
internal fun GrowDiskDialog(vmid: Int, disk: GuestDisk, onDismiss: () -> Unit, onGrow: (Int) -> Unit) {
    var add by remember { mutableStateOf("") }
    val gib = add.toIntOrNull()?.takeIf { it in 1..65536 }
    val newSize = (disk.size ?: 0L) + (gib ?: 0) * 1024L * MIB
    TypedConfirmDialog(
        title = stringResource(R.string.res_grow_title, disk.key),
        body = stringResource(R.string.res_grow_body, formatBytes(disk.size ?: 0L)),
        expected = vmid.toString(),
        confirmLabel = stringResource(R.string.res_grow),
        icon = Icons.Outlined.OpenInFull,
        extra = {
            Column {
                CtrlTextField(
                    value = add,
                    onValueChange = { add = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.res_grow_by)) },
                    supportingText = { if (gib != null) Text(stringResource(R.string.res_grow_new_size, formatBytes(newSize))) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.res_grow_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        onDismiss = onDismiss,
        onConfirm = { gib?.let(onGrow) },
    )
}

/** Clone: new ID (next free one suggested), name, linked or full copy and where full copies go. */
@Composable
internal fun CloneDialog(
    ref: GuestRef,
    sourceName: String,
    isTemplate: Boolean,
    onDismiss: () -> Unit,
    onClone: (newId: Int, name: String, full: Boolean, storage: String?) -> Unit,
) {
    val context = LocalContext.current
    val c = context.container
    val client = c.sessions.active.value?.client
    var newId by remember { mutableStateOf("") }
    var idTaken by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(if (sourceName.isBlank()) "" else "$sourceName-clone") }
    var full by remember { mutableStateOf(!isTemplate) }
    var storages by remember { mutableStateOf<List<StorageChoice>?>(null) }
    var storage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ref) {
        val cl = client ?: return@LaunchedEffect
        runCatching { ResourcesApi.nextId(cl) }.getOrNull()?.let { newId = it.toString() }
        storages = runCatching { BackupApi.storages(cl, ref.node, if (ref.type == GuestType.LXC) "rootdir" else "images") }
            .getOrDefault(emptyList()).filter { it.active && c.sessions.canWrite("/storage/${it.id}", BackupApi.STORAGE_PRIV) }
    }
    val id = newId.toIntOrNull()
    LaunchedEffect(id) {
        val cl = client ?: return@LaunchedEffect
        idTaken = id != null && !ResourcesApi.isIdFree(cl, id)
    }
    val idOk = id != null && id in 100..999_999_999 && !idTaken
    val nameOk = name.isBlank() || Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$").matches(name.trim())

    CtrlDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.ContentCopy, null) },
        title = { Text(stringResource(R.string.clone_title, ref.vmid.toString())) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CtrlTextField(
                    value = newId,
                    onValueChange = { newId = it.filter(Char::isDigit).take(9) },
                    label = { Text(stringResource(R.string.clone_new_id)) },
                    isError = !idOk,
                    supportingText = { if (idTaken) Text(stringResource(R.string.clone_id_taken)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                CtrlTextField(
                    value = name,
                    onValueChange = { name = it.take(63) },
                    label = { Text(stringResource(if (ref.type == GuestType.LXC) R.string.clone_hostname else R.string.clone_name)) },
                    isError = !nameOk,
                    supportingText = { if (!nameOk) Text(stringResource(R.string.clone_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.clone_full))
                        Text(
                            stringResource(if (isTemplate) R.string.clone_full_hint_template else R.string.clone_full_hint_guest),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Only templates can be cloned linked; normal guests are always copied in full.
                    CtrlSwitch(checked = full, onCheckedChange = { full = it }, enabled = isTemplate)
                }
                if (full) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.clone_storage), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = storage == null, onClick = { storage = null }, label = { Text(stringResource(R.string.clone_storage_same)) })
                        storages.orEmpty().forEach { s ->
                            FilterChip(selected = storage == s.id, onClick = { storage = s.id }, label = { Text(s.id) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { id?.let { onClone(it, name, full, storage) } }, enabled = idOk && nameOk) {
                Text(stringResource(R.string.clone))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
