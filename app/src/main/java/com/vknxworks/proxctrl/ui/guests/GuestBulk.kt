package com.vknxworks.proxctrl.ui.guests

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.guests.PowerAction
import com.vknxworks.proxctrl.data.guests.power
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.telemetry.Telemetry
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.ui.theme.Gutter

fun PowerAction.icon(): ImageVector = when (this) {
    PowerAction.START -> Icons.Filled.PlayArrow
    PowerAction.SHUTDOWN -> Icons.Filled.PowerSettingsNew
    PowerAction.REBOOT -> Icons.Filled.RestartAlt
    PowerAction.STOP -> Icons.Filled.Stop
    PowerAction.SUSPEND -> Icons.Filled.Pause
    PowerAction.RESUME -> Icons.Filled.PlayArrow
    PowerAction.HIBERNATE -> Icons.Filled.Bedtime
}

fun PowerAction.label(): Int = when (this) {
    PowerAction.START -> R.string.action_start
    PowerAction.SHUTDOWN -> R.string.action_shutdown
    PowerAction.REBOOT -> R.string.action_reboot
    PowerAction.STOP -> R.string.action_stop
    PowerAction.SUSPEND -> R.string.action_suspend
    PowerAction.RESUME -> R.string.action_resume
    PowerAction.HIBERNATE -> R.string.action_hibernate
}

private fun PowerAction.confirmTitle(): Int = when (this) {
    PowerAction.START -> R.string.bulk_confirm_title_start
    PowerAction.SHUTDOWN -> R.string.bulk_confirm_title_shutdown
    PowerAction.REBOOT -> R.string.bulk_confirm_title_reboot
    else -> R.string.bulk_confirm_title_stop
}

/** The four actions for the selected guests. */
@Composable
fun BulkActionRow(onAction: (PowerAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PowerAction.BULK.forEach { action ->
            FilledTonalButton(
                onClick = { onAction(action) },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(action.icon(), null, Modifier.size(20.dp))
                    Text(stringResource(action.label()), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Confirms a bulk power action and lists which guests it will run on. Guests it doesn't apply to
 * (already in that state, locked, templates, not allowed) are skipped and counted. Stop cuts power
 * immediately, so it needs an extra tick before the button works.
 */
@Composable
fun BulkPowerDialog(
    action: PowerAction,
    selected: List<Guest>,
    canRun: (Guest) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<Guest>) -> Unit,
) {
    val targets = remember(selected, action) { selected.filter(canRun) }
    val skipped = selected.size - targets.size
    var stopUnderstood by remember { mutableStateOf(false) }
    val isStop = action == PowerAction.STOP
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (isStop) Icons.Outlined.Warning else action.icon(), null, tint = if (isStop) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(action.confirmTitle(), targets.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (action) {
                    PowerAction.START -> Text(stringResource(R.string.bulk_confirm_body_start))
                    PowerAction.SHUTDOWN -> Text(stringResource(R.string.bulk_confirm_body_shutdown))
                    PowerAction.REBOOT -> Text(stringResource(R.string.bulk_confirm_body_reboot))
                    else -> Text(stringResource(R.string.bulk_stop_warning), color = MaterialTheme.colorScheme.error)
                }
                Text(
                    targets.joinToString("\n") { "${it.vmid}  ${it.name}" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (skipped > 0) {
                    Text(stringResource(R.string.bulk_skipped, skipped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isStop && targets.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { stopUnderstood = !stopUnderstood },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(stopUnderstood, onCheckedChange = { stopUnderstood = it })
                        Text(stringResource(R.string.bulk_stop_check))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(targets) },
                enabled = targets.isNotEmpty() && (!isStop || stopUnderstood),
            ) {
                Text(stringResource(action.label()), color = if (isStop) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Runs [action] on each guest in turn and returns a one-line summary for the snackbar. */
suspend fun runBulk(context: Context, client: PveClient, action: PowerAction, targets: List<Guest>): String {
    val results = targets.map { client.power(it, action) }
    // One event per guest type in the batch, never which guests or how many of each.
    targets.map { it.type }.distinct().forEach { trackPower(context.container.telemetry, action, it, bulk = true) }
    val ok = results.count { it.error == null }
    val label = context.getString(action.label())
    val failed = results.filter { it.error != null }
    return if (failed.isEmpty()) {
        context.getString(R.string.bulk_done, label, ok, targets.size)
    } else {
        context.getString(R.string.bulk_failed, label, failed.joinToString(", ") { "${it.guest.vmid} ${it.guest.name}" })
    }
}

/** Usage stats: which power action on which kind of guest, never the guest itself. */
fun trackPower(telemetry: Telemetry, action: PowerAction, type: GuestType, bulk: Boolean) {
    telemetry.track(
        "guest_power_action",
        mapOf("action" to action.name.lowercase(), "guest_type" to type.api, "bulk" to bulk.toString()),
    )
}
