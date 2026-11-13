package com.vknxworks.proxctrl.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.server.Server
import com.vknxworks.proxctrl.data.widgets.GuestTileLogic
import com.vknxworks.proxctrl.data.widgets.GuestTileTarget
import com.vknxworks.proxctrl.ui.components.ChoiceSheet
import com.vknxworks.proxctrl.ui.components.CtrlDialog
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.errorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where picking the tile's guest stands. */
private sealed interface PickStep {
    data object ChooseServer : PickStep
    data class Loading(val server: Server) : PickStep
    data class Guests(val server: Server, val guests: List<Guest>) : PickStep
    data class Failed(val server: Server, val error: PveError) : PickStep
}

/**
 * The guest the Quick Settings guest tile controls: the server first (when there are several), then
 * one of its VMs or containers. "None" turns the tile back into "pick a guest".
 */
@Composable
fun GuestTileSetting() {
    val context = LocalContext.current
    val c = context.container
    val target by c.guestTile.target.collectAsStateWithLifecycle()
    val servers by c.sessions.servers.collectAsStateWithLifecycle()
    var step by remember { mutableStateOf<PickStep?>(null) }
    val server = target?.let { t -> servers.firstOrNull { it.id == t.serverId } }

    val current = target
    val value = when {
        current == null || server == null -> stringResource(R.string.guest_tile_setting_none)
        else -> {
            val guest = stringResource(R.string.guest_tile_guest, current.vmid, current.name.ifBlank { current.vmid.toString() })
            if (servers.size > 1) stringResource(R.string.guest_tile_on_server, guest, server.name) else guest
        }
    }
    GroupRow(
        stringResource(R.string.guest_tile_setting),
        value,
        icon = Icons.Outlined.PowerSettingsNew,
        showChevron = true,
        onClick = {
            step = when {
                servers.size > 1 -> PickStep.ChooseServer
                servers.size == 1 -> PickStep.Loading(servers.first())
                else -> null
            }
        },
    )

    when (val s = step) {
        null -> Unit
        PickStep.ChooseServer -> ChoiceSheet(
            title = stringResource(R.string.guest_tile_pick_server),
            options = servers.map { it to it.name },
            selected = server,
            onDismiss = { step = null },
        ) { step = PickStep.Loading(it) }
        is PickStep.Loading -> {
            LaunchedEffect(s) {
                step = try {
                    val active = c.sessions.active.value
                    // The active server's client, or one for the other server (its login is read off the main thread).
                    val client = if (active?.server?.id == s.server.id) active.client else withContext(Dispatchers.IO) { c.sessions.clientFor(s.server) }
                    PickStep.Guests(s.server, GuestTileLogic.pickable(client.get(GuestTileLogic.RESOURCES_PATH, GuestTileLogic.RESOURCES_QUERY)))
                } catch (e: PveError) {
                    PickStep.Failed(s.server, e)
                }
            }
            CtrlDialog(
                onDismissRequest = { step = null },
                title = { Text(stringResource(R.string.guest_tile_pick_guest)) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.guest_tile_loading, s.server.name))
                    }
                },
                confirmButton = { TextButton(onClick = { step = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        is PickStep.Guests -> {
            val none: Guest? = null
            val selected = s.guests.firstOrNull { g -> current != null && current.serverId == s.server.id && g.vmid == current.vmid && g.type == current.guestType }
            ChoiceSheet(
                title = stringResource(R.string.guest_tile_pick_guest),
                options = listOf(none to stringResource(R.string.guest_tile_setting_none)) +
                    s.guests.map { it to stringResource(R.string.guest_tile_guest, it.vmid, it.name.ifBlank { it.vmid.toString() }) },
                // Nothing picked on this server: "None" is the current choice.
                selected = selected,
                onDismiss = { step = null },
            ) { g ->
                c.guestTile.set(g?.let { GuestTileTarget(serverId = s.server.id, type = it.type.api, vmid = it.vmid, name = it.name) })
                step = null
            }
        }
        is PickStep.Failed -> CtrlDialog(
            onDismissRequest = { step = null },
            title = { Text(stringResource(R.string.guest_tile_pick_guest)) },
            text = {
                Text(
                    errorMessage(context, s.error, s.server.host, s.server.auth),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = { TextButton(onClick = { step = null }) { Text(stringResource(R.string.done)) } },
        )
    }
}
