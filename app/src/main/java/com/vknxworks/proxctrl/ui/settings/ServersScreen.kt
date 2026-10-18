package com.vknxworks.proxctrl.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.server.AuthMethod
import com.vknxworks.proxctrl.data.server.Server
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.GroupSwitch
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.nav.AddServerRoute
import com.vknxworks.proxctrl.ui.nav.EditServerRoute
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import com.vknxworks.proxctrl.ui.theme.Health

/** How a server is signed in to, for subtitles. */
@Composable
fun authSummary(server: Server): String = when {
    server.auth == AuthMethod.TOKEN -> stringResource(R.string.auth_summary_token, server.tokenId)
    server.usesTfa -> stringResource(R.string.auth_summary_password_tfa, server.username)
    else -> stringResource(R.string.auth_summary_password, server.username)
}

/** All saved servers: switch between them, change one, set read-only mode, or remove one. */
@Composable
fun ServersScreen() {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val servers by c.sessions.servers.collectAsStateWithLifecycle()
    val active by c.sessions.active.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<Server?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.servers_title))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            servers.forEach { server ->
                val isActive = server.id == active?.server?.id
                Group(server.name) {
                    GroupRow(
                        server.url.removePrefix("https://"),
                        listOfNotNull(
                            authSummary(server),
                            server.pveVersion.takeIf { it.isNotBlank() }?.let { stringResource(R.string.pve_version, it) },
                            stringResource(if (server.certPin.isNotBlank()) R.string.cert_pinned else R.string.cert_system),
                        ).joinToString("  ·  "),
                        icon = Icons.Filled.Dns,
                        trailing = if (isActive) {
                            { Icon(Icons.Filled.CheckCircle, stringResource(R.string.active_server), tint = Health.ok) }
                        } else {
                            null
                        },
                    )
                    if (!isActive) {
                        GroupRow(stringResource(R.string.switch_to), icon = Icons.Filled.SwapHoriz, onClick = { c.sessions.switchTo(server.id) })
                    }
                    GroupSwitch(
                        stringResource(R.string.read_only_mode),
                        stringResource(R.string.read_only_mode_subtitle),
                        checked = server.readOnly,
                        icon = Icons.Filled.Visibility,
                    ) { c.sessions.setReadOnly(server.id, it) }
                    GroupRow(stringResource(R.string.edit_server), icon = Icons.Filled.Edit, showChevron = true, onClick = { nav.navigate(EditServerRoute(server.id)) })
                    GroupRow(
                        stringResource(R.string.remove_server),
                        icon = Icons.Filled.Delete,
                        iconTint = MaterialTheme.colorScheme.error,
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { removing = server },
                    )
                }
            }
            Group {
                GroupRow(stringResource(R.string.add_server), icon = Icons.Filled.Add, showChevron = true, onClick = { nav.navigate(AddServerRoute) })
            }
        }
    }

    removing?.let { server ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_server_title, server.name)) },
            text = { Text(stringResource(R.string.remove_server_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    c.sessions.remove(server.id)
                }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
