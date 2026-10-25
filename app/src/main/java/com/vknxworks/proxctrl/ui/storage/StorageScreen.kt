package com.vknxworks.proxctrl.ui.storage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.overview.NodeEntry
import com.vknxworks.proxctrl.data.storage.DiskApi
import com.vknxworks.proxctrl.data.storage.StorageApi
import com.vknxworks.proxctrl.data.storage.StorageInfo
import com.vknxworks.proxctrl.data.storage.ZfsPool
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.EmptyState
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.LocalBottomClearance
import com.vknxworks.proxctrl.ui.components.PollWhileVisible
import com.vknxworks.proxctrl.ui.components.StaleBanner
import com.vknxworks.proxctrl.ui.components.StatusDot
import com.vknxworks.proxctrl.ui.components.TabTitle
import com.vknxworks.proxctrl.ui.components.UsageBar
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.components.percent
import com.vknxworks.proxctrl.ui.components.usageColor
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import com.vknxworks.proxctrl.ui.theme.Gutter
import com.vknxworks.proxctrl.ui.theme.Health
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer

private const val POLL_MS = 30_000L

/** What the Storage tab shows for one node. */
private data class NodeStorage(val node: String, val online: Boolean, val storages: List<StorageInfo>, val pools: List<ZfsPool>)

private suspend fun loadStorage(client: PveClient): List<NodeStorage> = coroutineScope {
    val nodes = client.get("/nodes", ListSerializer(NodeEntry.serializer())).sortedBy { it.node }
    nodes.map { n ->
        async {
            if (!n.isOnline) return@async NodeStorage(n.node, false, emptyList(), emptyList())
            val storages = StorageApi.list(client, n.node)
            // ZFS pools need Sys.Audit on /; without it the section is simply left out.
            val pools = runCatching { DiskApi.zfsPools(client, n.node) }.getOrDefault(emptyList())
            NodeStorage(n.node, true, storages, pools)
        }
    }.awaitAll()
}

/** Storages with their usage and content types, the node's disks and pools, and backup jobs. */
@Composable
fun StorageScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client

    var data by remember(server?.id) { mutableStateOf<List<NodeStorage>?>(null) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var updatedAt by remember { mutableLongStateOf(0L) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    PollWhileVisible(Triple(server?.id, client, reload), POLL_MS) {
        val cl = client ?: return@PollWhileVisible false
        try {
            data = loadStorage(cl)
            updatedAt = System.currentTimeMillis()
            error = null
            true
        } catch (e: PveError) {
            error = e
            false
        } finally {
            refreshing = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabTitle(stringResource(R.string.tab_storage))
        val err = error
        if (access.error != null) {
            ConnectionBanner()
        } else if (err != null && data != null) {
            StaleBanner(updatedAt, err, server?.host.orEmpty(), server?.auth) { reload++ }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reload++ },
            modifier = Modifier.weight(1f),
        ) {
            val nodes = data
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomClearance.current + 16.dp)) {
                when {
                    nodes == null && err != null -> item { ErrorText(err) }
                    nodes == null -> item { LoadingBox() }
                    nodes.all { it.storages.isEmpty() } -> item {
                        EmptyState(
                            icon = Icons.Outlined.Storage,
                            title = stringResource(R.string.storage_none),
                            body = stringResource(R.string.storage_none_hint),
                            modifier = Modifier.height(360.dp),
                        )
                    }
                    else -> {
                        val many = nodes.size > 1
                        nodes.forEach { n ->
                            item(key = "storages-${n.node}") {
                                Group(
                                    title = if (many) stringResource(R.string.storage_node_storages, n.node) else stringResource(R.string.storage_storages),
                                ) {
                                    if (!n.online) {
                                        GroupRow(stringResource(R.string.storage_node_offline, n.node))
                                    }
                                    n.storages.forEachIndexed { i, s ->
                                                    StorageRow(s, settings.diskWarnPercent) { nav.navigate(StorageContentRoute(s.node, s.id)) }
                                    }
                                }
                            }
                            if (n.online) {
                                item(key = "hardware-${n.node}") {
                                    Group(
                                        title = if (many) stringResource(R.string.storage_node_hardware, n.node) else stringResource(R.string.storage_hardware),
                                    ) {
                                        GroupRow(
                                            title = stringResource(R.string.storage_disks),
                                            subtitle = stringResource(R.string.storage_disks_subtitle),
                                            icon = Icons.Outlined.SdStorage,
                                            showChevron = true,
                                            onClick = { nav.navigate(StorageDisksRoute(n.node)) },
                                        )
                                        n.pools.forEach { p ->
                                            val healthy = p.health.equals("ONLINE", ignoreCase = true)
                                            GroupRow(
                                                title = stringResource(R.string.storage_zfs_pool, p.name),
                                                subtitle = stringResource(
                                                    R.string.storage_zfs_pool_subtitle,
                                                    p.health.ifBlank { "?" },
                                                    formatBytes(p.alloc),
                                                    formatBytes(p.size),
                                                ),
                                                icon = Icons.Outlined.Storage,
                                                iconTint = if (healthy) MaterialTheme.colorScheme.primary else Health.critical,
                                                showChevron = true,
                                                onClick = { nav.navigate(StorageZfsRoute(n.node, p.name)) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "jobs") {
                            Group(stringResource(R.string.storage_backups_group)) {
                                GroupRow(
                                    title = stringResource(R.string.storage_backup_jobs),
                                    subtitle = stringResource(R.string.storage_backup_jobs_subtitle),
                                    icon = Icons.Outlined.Schedule,
                                    showChevron = true,
                                    onClick = { nav.navigate(StorageBackupJobsRoute) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageRow(s: StorageInfo, warnAt: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when {
                    !s.enabled -> "stopped"
                    s.active -> "online"
                    else -> "unknown"
                },
            )
            Spacer(Modifier.width(10.dp))
            Text(s.id, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(s.type, if (s.shared) stringResource(R.string.storage_shared) else null).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val f = s.fraction
        if (s.active && s.total > 0) {
            Spacer(Modifier.height(8.dp))
            UsageBar(f, usageColor(f, warnAt, MaterialTheme.colorScheme.primary), height = 6.dp)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.storage_usage, formatBytes(s.used), formatBytes(s.total), percent(f ?: 0.0)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val state = when {
            !s.enabled -> stringResource(R.string.storage_disabled)
            !s.active -> stringResource(R.string.storage_not_active)
            else -> null
        }
        if (state != null) {
            Text(state, style = MaterialTheme.typography.bodySmall, color = if (s.enabled) Health.critical else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.content.isNotEmpty()) {
            Text(
                s.content.map { stringResource(contentLabel(it)) }.distinct().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
