package com.vknxworks.proxctrl.ui.setup

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.api.Permissions
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.theme.Health

/** One thing the app can do, and the privilege it needs (anywhere under [path]). */
private data class Capability(@StringRes val label: Int, val path: String, val priv: String)

/**
 * The privileges the app looks for. Guest-agent data needs VM.GuestAgent.Audit on Proxmox VE 9 and
 * VM.Monitor on 8 (9 removed VM.Monitor).
 */
private fun capabilities(pveMajor: Int) = listOf(
    Capability(R.string.cap_guests, "/vms", "VM.Audit"),
    Capability(R.string.cap_node, "/nodes", "Sys.Audit"),
    Capability(R.string.cap_storage, "/storage", "Datastore.Audit"),
    Capability(R.string.cap_agent, "/vms", if (pveMajor in 1..8) "VM.Monitor" else "VM.GuestAgent.Audit"),
    Capability(R.string.cap_power, "/vms", "VM.PowerMgmt"),
    Capability(R.string.cap_console, "/vms", "VM.Console"),
    Capability(R.string.cap_snapshots, "/vms", "VM.Snapshot"),
    Capability(R.string.cap_backups, "/vms", "VM.Backup"),
    Capability(R.string.cap_shell, "/nodes", "Sys.Console"),
    Capability(R.string.cap_updates, "/nodes", "Sys.Modify"),
    Capability(R.string.cap_node_power, "/nodes", "Sys.PowerMgmt"),
)

/** Lists what the signed-in user or token is allowed to do, with the privilege behind each item. */
@Composable
fun AccessSummary(permissions: Permissions, pveMajor: Int) {
    val footer = if (permissions.isEmpty) stringResource(R.string.access_none_warning) else stringResource(R.string.access_footer)
    Group(stringResource(R.string.access_title), footer = footer) {
        capabilities(pveMajor).forEach { cap ->
            val ok = permissions.hasAnywhere(cap.path, cap.priv)
            GroupRow(
                title = stringResource(cap.label),
                subtitle = "${stringResource(if (ok) R.string.allowed else R.string.not_allowed)}  ·  ${cap.priv}",
                titleColor = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                trailing = {
                    if (ok) Icon(Icons.Filled.CheckCircle, null, tint = Health.ok)
                    else Icon(Icons.Outlined.Block, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
        }
    }
}
