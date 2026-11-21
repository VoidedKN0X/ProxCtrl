package com.vknxworks.proxctrl.ui.guests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoveUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.ui.components.CtrlDialog
import com.vknxworks.proxctrl.ui.components.GroupSwitch
import com.vknxworks.proxctrl.ui.components.ShieldScreen

/**
 * Moves a guest to another node of the cluster. [targets] are the other nodes that are online.
 * A running VM moves live; a running container is shut down, moved and started again.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MigrateDialog(
    label: String,
    type: GuestType,
    running: Boolean,
    targets: List<String>,
    onDismiss: () -> Unit,
    onMigrate: (target: String, withLocalDisks: Boolean) -> Unit,
) {
    ShieldScreen(sensitiveData = false)
    var target by remember { mutableStateOf(targets.singleOrNull()) }
    var localDisks by remember { mutableStateOf(false) }
    CtrlDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.MoveUp, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.migrate_title, label)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        when {
                            !running -> R.string.migrate_body_stopped
                            type == GuestType.QEMU -> R.string.migrate_body_vm
                            else -> R.string.migrate_body_lxc
                        },
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.migrate_target), style = MaterialTheme.typography.labelLarge)
                if (targets.isEmpty()) {
                    Text(stringResource(R.string.migrate_no_target), color = MaterialTheme.colorScheme.error)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    targets.forEach { n -> FilterChip(selected = target == n, onClick = { target = n }, label = { Text(n) }) }
                }
                if (running && type == GuestType.QEMU) {
                    GroupSwitch(
                        title = stringResource(R.string.migrate_local_disks),
                        subtitle = stringResource(R.string.migrate_local_disks_hint),
                        checked = localDisks,
                        onChange = { localDisks = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { target?.let { onMigrate(it, localDisks) } }, enabled = target != null) { Text(stringResource(R.string.migrate_confirm)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
