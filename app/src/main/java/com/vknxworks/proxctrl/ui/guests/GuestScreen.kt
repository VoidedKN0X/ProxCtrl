package com.vknxworks.proxctrl.ui.guests

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.theme.Gutter

/**
 * A guest's page. For now its row from the list; the full page (summary, graphs, power, console,
 * snapshots, backups) is the next stage.
 */
@Composable
fun GuestScreen(node: String, type: String, vmid: Int) {
    val c = LocalContext.current.container
    val state by c.guests.state.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val guest = state.guests.firstOrNull { it.vmid == vmid && it.type.api == type }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(guest?.let { "${it.vmid}  ${it.name}" } ?: vmid.toString())
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            if (guest != null) {
                GuestRowDetailed(guest, state.tagColors, settings.diskWarnPercent, selecting = false, selected = false, onClick = {}, onLongClick = null)
                Text(
                    listOf(statusLabel(guest), node, stringResource(if (guest.type.api == "lxc") R.string.lxc else R.string.qemu)).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.guest_detail_coming),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Gutter),
            )
        }
    }
}
