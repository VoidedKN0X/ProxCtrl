package com.vknxworks.proxctrl.ui.storage

import com.vknxworks.proxctrl.ui.components.CtrlDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.storage.DiskApi
import com.vknxworks.proxctrl.data.storage.DiskInfo
import com.vknxworks.proxctrl.data.storage.SmartInfo
import com.vknxworks.proxctrl.data.storage.ThinPool
import com.vknxworks.proxctrl.data.storage.VolumeGroup
import com.vknxworks.proxctrl.data.storage.ZfsPool
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.UsageBar
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.components.percent
import com.vknxworks.proxctrl.ui.components.usageColor
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import com.vknxworks.proxctrl.ui.theme.Health
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** A node's physical disks with S.M.A.R.T. health and wearout, its ZFS pools and its LVM. */
@Composable
fun DisksScreen(node: String) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client

    var disks by remember { mutableStateOf<Result<List<DiskInfo>>?>(null) }
    var pools by remember { mutableStateOf<List<ZfsPool>>(emptyList()) }
    var vgs by remember { mutableStateOf<List<VolumeGroup>>(emptyList()) }
    var thin by remember { mutableStateOf<List<ThinPool>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var smartFor by remember { mutableStateOf<DiskInfo?>(null) }

    LaunchedEffect(node, server?.id, reload) {
        val cl = client ?: return@LaunchedEffect
        coroutineScope {
            val d = async { runCatching { DiskApi.disks(cl, node) } }
            val z = async { runCatching { DiskApi.zfsPools(cl, node) }.getOrDefault(emptyList()) }
            val l = async { runCatching { DiskApi.volumeGroups(cl, node) }.getOrDefault(emptyList()) }
            val t = async { runCatching { DiskApi.thinPools(cl, node) }.getOrDefault(emptyList()) }
            disks = d.await()
            pools = z.await()
            vgs = l.await()
            thin = t.await()
        }
        refreshing = false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.storage_disks_title, node))
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reload++ },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                if (access.error != null) ConnectionBanner()
                val result = disks
                when {
                    result == null -> LoadingBox()
                    result.isFailure -> result.exceptionOrNull()?.let { ErrorText(it) }
                    else -> Group(stringResource(R.string.storage_disks), footer = stringResource(R.string.storage_disks_footer)) {
                        val list = result.getOrDefault(emptyList())
                        if (list.isEmpty()) GroupRow(stringResource(R.string.storage_disks_none))
                        list.forEach { d -> DiskRow(d) { smartFor = d } }
                    }
                }
                if (pools.isNotEmpty()) {
                    Group(stringResource(R.string.storage_zfs)) {
                        pools.forEach { p ->
                            val healthy = p.health.equals("ONLINE", ignoreCase = true)
                            Column(
                                Modifier.fillMaxWidth().clickable { nav.navigate(StorageZfsRoute(node, p.name)) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                    Text(
                                        p.health.ifBlank { "?" },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (healthy) MaterialTheme.colorScheme.primary else Health.critical,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                UsageBar(p.fraction, usageColor(p.fraction, settings.diskWarnPercent, MaterialTheme.colorScheme.primary), height = 6.dp)
                                Text(
                                    listOfNotNull(
                                        stringResource(R.string.storage_usage, formatBytes(p.alloc), formatBytes(p.size), percent(p.fraction ?: 0.0)),
                                        p.frag?.let { stringResource(R.string.storage_zfs_frag, it) },
                                        p.dedup?.takeIf { it > 1.0 }?.let { stringResource(R.string.storage_zfs_dedup, String.format(java.util.Locale.getDefault(), "%.2f", it)) },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
                if (vgs.isNotEmpty()) {
                    Group(stringResource(R.string.storage_lvm)) {
                        vgs.forEach { vg ->
                            UsageItem(
                                title = vg.name,
                                fraction = vg.fraction,
                                warnAt = settings.diskWarnPercent,
                                detail = stringResource(R.string.storage_lvm_detail, formatBytes(vg.size - vg.free), formatBytes(vg.size), formatBytes(vg.free)),
                                extra = vg.pvs.joinToString("\n") { (name, size) -> "$name  ${formatBytes(size)}" }.takeIf { it.isNotBlank() },
                            )
                        }
                    }
                }
                if (thin.isNotEmpty()) {
                    Group(stringResource(R.string.storage_lvmthin)) {
                        thin.forEach { t ->
                            UsageItem(
                                title = "${t.vg}/${t.lv}",
                                fraction = t.fraction,
                                warnAt = settings.diskWarnPercent,
                                detail = stringResource(R.string.storage_usage, formatBytes(t.used), formatBytes(t.size), percent(t.fraction ?: 0.0)),
                                extra = t.metaFraction?.let { stringResource(R.string.storage_lvmthin_meta, percent(it), formatBytes(t.metaSize)) },
                            )
                        }
                    }
                }
            }
        }
    }

    smartFor?.let { d -> SmartDialog(node, d) { smartFor = null } }
}

@Composable
private fun UsageItem(title: String, fraction: Double?, warnAt: Int, detail: String, extra: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        UsageBar(fraction, usageColor(fraction, warnAt, MaterialTheme.colorScheme.primary), height = 6.dp)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (extra != null) {
            Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiskRow(d: DiskInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val healthy = d.healthy
        Icon(
            when (healthy) {
                true -> Icons.Outlined.CheckCircle
                false -> Icons.Outlined.Error
                null -> Icons.Outlined.Help
            },
            null,
            Modifier.size(22.dp),
            tint = when (healthy) {
                true -> Health.ok
                false -> Health.critical
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOf(d.devpath, d.model.ifBlank { d.vendor }).filter { it.isNotBlank() }.joinToString("  "),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    d.type.takeIf { it.isNotBlank() }?.uppercase(),
                    formatBytes(d.size),
                    stringResource(R.string.storage_disk_health, d.health.ifBlank { "?" }),
                    d.wearoutPercent?.let { stringResource(R.string.storage_disk_wearout, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (healthy == false) Health.critical else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (d.usedBy.isNotBlank()) {
                Text(stringResource(R.string.storage_disk_used_by, d.usedBy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** S.M.A.R.T. values of one disk: the attribute table for ATA disks, Proxmox's text for NVMe and SAS. */
@Composable
private fun SmartDialog(node: String, disk: DiskInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val client = c.sessions.active.collectAsStateWithLifecycle().value?.client
    var smart by remember { mutableStateOf<Result<SmartInfo>?>(null) }
    LaunchedEffect(disk.devpath) {
        val cl = client ?: return@LaunchedEffect
        smart = runCatching { DiskApi.smart(cl, node, disk.devpath) }
    }
    CtrlDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.SdStorage, null) },
        title = { Text(stringResource(R.string.storage_smart_title, disk.devpath)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val r = smart
                when {
                    r == null -> LoadingBox()
                    r.isFailure -> {
                        val e = r.exceptionOrNull()
                        val server = c.sessions.active.value?.server
                        Text(
                            if (e != null) errorMessage(context, e, server?.host.orEmpty(), server?.auth) else "",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {
                        val s = r.getOrNull() ?: return@Column
                        Text(stringResource(R.string.storage_disk_health, s.health.ifBlank { disk.health.ifBlank { "?" } }), style = MaterialTheme.typography.titleSmall)
                        disk.wearoutPercent?.let {
                            Text(stringResource(R.string.storage_disk_wearout, it), style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (s.attributes.isNotEmpty()) {
                            Column(Modifier.horizontalScroll(rememberScrollState())) {
                                Text(
                                    stringResource(R.string.storage_smart_header),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                s.attributes.forEach { a ->
                                    val failing = a.failing.isNotBlank() && a.failing != "-"
                                    Text(
                                        "${a.id.padStart(3)} ${a.name.take(28).padEnd(28)} ${a.value.padStart(5)} ${a.worst.padStart(5)} ${a.threshold.padStart(5)}  ${a.raw}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = if (failing) Health.critical else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        } else if (!s.text.isNullOrBlank()) {
                            Text(s.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                        } else {
                            Text(stringResource(R.string.storage_smart_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
