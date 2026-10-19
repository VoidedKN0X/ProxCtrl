package com.vknxworks.proxctrl.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.overview.AttentionItem
import com.vknxworks.proxctrl.data.overview.Severity
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.components.percent
import com.vknxworks.proxctrl.ui.components.relativeTime
import com.vknxworks.proxctrl.ui.theme.Gutter
import com.vknxworks.proxctrl.ui.theme.Health

/** Where tapping an attention item goes. */
sealed interface AttentionTarget {
    data class GuestPage(val guest: Guest) : AttentionTarget
    data object Storage : AttentionTarget
    data object Tasks : AttentionTarget
    data object Node : AttentionTarget
    data object Guests : AttentionTarget
}

private const val COLLAPSED_COUNT = 5

fun AttentionItem.target(): AttentionTarget? = when (this) {
    is AttentionItem.GuestDisk -> AttentionTarget.GuestPage(guest)
    is AttentionItem.StoppedOnBoot -> AttentionTarget.GuestPage(guest)
    is AttentionItem.NodeMemory, is AttentionItem.NodeRootDisk, is AttentionItem.NodeOffline,
    is AttentionItem.Subscription, is AttentionItem.Updates, is AttentionItem.HaProblem -> AttentionTarget.Node
    is AttentionItem.FailedTasks, is AttentionItem.BackupFailed -> AttentionTarget.Tasks
    is AttentionItem.StorageFull, is AttentionItem.StorageUnavailable -> AttentionTarget.Storage
    is AttentionItem.BackupOld -> guests.singleOrNull()?.let { AttentionTarget.GuestPage(it) } ?: AttentionTarget.Guests
    is AttentionItem.NotBackedUp -> guests.singleOrNull()?.let { AttentionTarget.GuestPage(it) } ?: AttentionTarget.Guests
}

private fun guestName(g: Guest) = "${g.vmid} ${g.name}".trim()

private fun names(guests: List<Guest>, max: Int = 6): String {
    val shown = guests.take(max).joinToString(", ") { guestName(it) }
    return if (guests.size > max) "$shown, …" else shown
}

@Composable
private fun title(item: AttentionItem): String = when (item) {
    is AttentionItem.GuestDisk -> stringResource(R.string.att_guest_disk, guestName(item.guest), percent(item.fraction))
    is AttentionItem.NodeMemory -> stringResource(R.string.att_node_mem, item.node, percent(item.fraction))
    is AttentionItem.NodeRootDisk -> stringResource(R.string.att_node_root, item.node, percent(item.fraction))
    is AttentionItem.NodeOffline -> stringResource(R.string.att_node_offline, item.node)
    is AttentionItem.StoppedOnBoot -> stringResource(R.string.att_onboot, guestName(item.guest))
    is AttentionItem.FailedTasks -> pluralStringResource(R.plurals.att_failed_tasks, item.tasks.size, item.tasks.size)
    is AttentionItem.BackupFailed -> stringResource(if (item.task.isError) R.string.att_backup_failed else R.string.att_backup_warnings, item.task.node)
    is AttentionItem.BackupOld -> pluralStringResource(R.plurals.att_backup_old, item.guests.size, item.guests.size, item.maxAgeDays)
    is AttentionItem.NotBackedUp -> pluralStringResource(R.plurals.att_not_backed_up, item.guests.size, item.guests.size)
    is AttentionItem.StorageFull -> stringResource(R.string.att_storage_full, item.storage.storage, percent(item.fraction))
    is AttentionItem.StorageUnavailable -> stringResource(R.string.att_storage_down, item.storage.storage)
    is AttentionItem.Subscription -> stringResource(R.string.att_subscription, item.node, item.status)
    is AttentionItem.Updates -> pluralStringResource(R.plurals.att_updates, item.count, item.count, item.node)
    is AttentionItem.HaProblem -> if (item.entry.type == "quorum") stringResource(R.string.att_ha_quorum)
    else stringResource(R.string.att_ha, item.entry.sid.ifBlank { item.entry.id }, item.entry.state)
}

@Composable
private fun detail(item: AttentionItem): String? = when (item) {
    is AttentionItem.GuestDisk -> stringResource(R.string.att_guest_disk_detail, formatBytes(item.guest.disk), formatBytes(item.guest.maxDisk))
    is AttentionItem.NodeOffline -> stringResource(R.string.att_node_offline_detail)
    is AttentionItem.StoppedOnBoot -> stringResource(R.string.att_onboot_detail)
    is AttentionItem.FailedTasks -> item.tasks.first().let { t ->
        stringResource(R.string.att_failed_tasks_detail, listOf(t.type, t.id).filter { it.isNotBlank() }.joinToString(" "), relativeTime(t.starttime * 1000))
    }
    is AttentionItem.BackupFailed -> stringResource(R.string.att_backup_failed_detail, relativeTime(item.task.starttime * 1000), item.task.status)
    is AttentionItem.BackupOld -> names(item.guests)
    is AttentionItem.NotBackedUp -> names(item.guests)
    is AttentionItem.StorageFull -> stringResource(
        R.string.att_storage_detail,
        formatBytes(item.storage.disk.toLong()),
        formatBytes(item.storage.maxdisk.toLong()),
        item.storage.node,
    )
    is AttentionItem.StorageUnavailable -> stringResource(R.string.att_storage_down_detail, item.storage.node, item.storage.status)
    is AttentionItem.Subscription -> stringResource(R.string.att_subscription_detail)
    is AttentionItem.Updates -> stringResource(R.string.att_updates_detail)
    is AttentionItem.HaProblem -> item.entry.status.takeIf { it.isNotBlank() }
    else -> null
}

/** Severity icons differ in shape as well as colour, and carry a label for screen readers. */
@Composable
private fun SeverityIcon(severity: Severity) {
    val (icon, tint, label) = when (severity) {
        Severity.CRITICAL -> Triple(Icons.Filled.Error, Health.critical, R.string.sev_critical)
        Severity.WARNING -> Triple(Icons.Filled.Warning, Health.warn, R.string.sev_warning)
        Severity.INFO -> Triple(Icons.Filled.Info, MaterialTheme.colorScheme.onSurfaceVariant, R.string.sev_info)
    }
    Icon(icon, stringResource(label), Modifier.size(22.dp), tint = tint)
}

/** The attention list at the top of the Overview. Critical first; collapsed to five with "Show all". */
@Composable
fun AttentionCard(items: List<AttentionItem>, serverName: String, onOpen: (AttentionTarget) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
        Text(
            stringResource(R.string.attention_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            if (items.isEmpty()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(22.dp), tint = Health.ok)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(stringResource(R.string.attention_all_good), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.attention_all_good_body, serverName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Column(Modifier.padding(vertical = 4.dp)) {
                    val shown = if (expanded) items else items.take(COLLAPSED_COUNT)
                    shown.forEachIndexed { i, item ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        AttentionRow(item, onOpen)
                    }
                    if (items.size > COLLAPSED_COUNT) {
                        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(start = 40.dp)) {
                            Text(if (expanded) stringResource(R.string.attention_show_less) else stringResource(R.string.attention_show_all, items.size))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttentionRow(item: AttentionItem, onOpen: (AttentionTarget) -> Unit) {
    val target = item.target()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (target != null) Modifier.clickable { onOpen(target) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SeverityIcon(item.severity)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title(item), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail(item)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (target != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
