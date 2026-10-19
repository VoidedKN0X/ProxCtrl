package com.vknxworks.proxctrl.ui.storage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.storage.DiskApi
import com.vknxworks.proxctrl.data.storage.ZfsDetail
import com.vknxworks.proxctrl.data.storage.ZfsDevice
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.theme.Health

/** One ZFS pool: state, what Proxmox says about it, the last scrub, errors, and every device. */
@Composable
fun ZfsPoolScreen(node: String, name: String) {
    val c = LocalContext.current.container
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client

    var detail by remember { mutableStateOf<ZfsDetail?>(null) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(node, name, server?.id, reload) {
        val cl = client ?: return@LaunchedEffect
        try {
            detail = DiskApi.zfsDetail(cl, node, name)
            error = null
        } catch (e: PveError) {
            error = e
        }
        refreshing = false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.storage_zfs_pool, name))
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reload++ },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                if (access.error != null) ConnectionBanner()
                val err = error
                val d = detail
                when {
                    d == null && err != null -> ErrorText(err)
                    d == null -> LoadingBox()
                    else -> {
                        val healthy = d.state.equals("ONLINE", ignoreCase = true)
                        Group(stringResource(R.string.storage_zfs_status)) {
                            GroupRow(
                                title = stringResource(R.string.storage_zfs_state),
                                trailing = {
                                    Text(
                                        d.state.ifBlank { "?" },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (healthy) MaterialTheme.colorScheme.primary else Health.critical,
                                    )
                                },
                            )
                            if (d.status.isNotBlank()) GroupRow(stringResource(R.string.storage_zfs_status_text), d.status)
                            if (d.action.isNotBlank()) GroupRow(stringResource(R.string.storage_zfs_action), d.action)
                            GroupRow(stringResource(R.string.storage_zfs_scan), d.scan.ifBlank { stringResource(R.string.storage_zfs_scan_none) })
                            if (d.errors.isNotBlank()) GroupRow(stringResource(R.string.storage_zfs_errors), d.errors)
                        }
                        if (d.devices.isNotEmpty()) {
                            Group(stringResource(R.string.storage_zfs_devices), footer = stringResource(R.string.storage_zfs_devices_footer)) {
                                d.devices.forEach { dev -> DeviceRow(dev) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(dev: ZfsDevice) {
    val ok = dev.state.isBlank() || dev.state.equals("ONLINE", ignoreCase = true) || dev.state.equals("AVAIL", ignoreCase = true)
    val errors = dev.read + dev.write + dev.cksum
    Column(Modifier.fillMaxWidth().padding(start = 16.dp + (dev.depth * 16).dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dev.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            if (dev.state.isNotBlank()) {
                Text(dev.state, style = MaterialTheme.typography.labelMedium, color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else Health.critical)
            }
        }
        if (dev.state.isNotBlank()) {
            Text(
                stringResource(R.string.storage_zfs_counters, dev.read, dev.write, dev.cksum),
                style = MaterialTheme.typography.bodySmall,
                color = if (errors > 0) Health.critical else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (dev.msg.isNotBlank()) {
            Text(dev.msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
