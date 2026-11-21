package com.vknxworks.proxctrl.ui.node

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.cluster.CephState
import com.vknxworks.proxctrl.data.cluster.ClusterApi
import com.vknxworks.proxctrl.data.cluster.ClusterState
import com.vknxworks.proxctrl.data.cluster.ReplicationJob
import com.vknxworks.proxctrl.data.overview.HaEntry
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.components.percent
import com.vknxworks.proxctrl.ui.components.relativeTime
import com.vknxworks.proxctrl.ui.theme.Gutter
import com.vknxworks.proxctrl.ui.theme.Health
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The cluster, for servers with more than one node: members and quorum, and (each only when it is
 * in use) high availability, storage replication and Ceph. Read-only.
 */
@Composable
fun ClusterScreen(node: String) {
    val context = LocalContext.current
    val c = context.container
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client

    var state by remember { mutableStateOf<ClusterState?>(null) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var ha by remember { mutableStateOf<List<HaEntry>>(emptyList()) }
    var replication by remember { mutableStateOf<List<ReplicationJob>>(emptyList()) }
    var ceph by remember { mutableStateOf<CephState?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(server?.id, node, reload) {
        val cl = client ?: return@LaunchedEffect
        coroutineScope {
            val st = async { runCatching { ClusterApi.state(cl) } }
            // Each of these is simply left out when it isn't set up or this sign-in can't see it.
            val h = async { runCatching { ClusterApi.ha(cl) }.getOrDefault(emptyList()) }
            val r = async { runCatching { ClusterApi.replication(cl, node) }.getOrDefault(emptyList()) }
            val ce = async { runCatching { ClusterApi.ceph(cl, node) }.getOrNull() }
            st.await().onSuccess { state = it; error = null }.onFailure { e -> error = e as? PveError }
            ha = h.await()
            replication = r.await()
            ceph = ce.await()?.takeIf { it.health.isNotBlank() }
        }
        refreshing = false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.cluster_title))
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; reload++ }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                if (access.error != null) ConnectionBanner()
                val st = state
                val err = error
                when {
                    st == null && err == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    st == null && err != null -> Text(
                        errorMessage(context, err, server?.host.orEmpty(), server?.auth),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(Gutter),
                    )
                    st != null -> Group(st.name?.let { stringResource(R.string.cluster_named, it) } ?: stringResource(R.string.cluster_members)) {
                        GroupRow(
                            title = stringResource(R.string.cluster_quorum),
                            subtitle = stringResource(if (st.quorate) R.string.cluster_quorate else R.string.cluster_not_quorate),
                            titleColor = if (st.quorate) MaterialTheme.colorScheme.onSurface else Health.critical,
                        )
                        st.members.forEach { m ->
                            GroupRow(
                                title = m.name + if (m.local) "  ·  " + stringResource(R.string.cluster_this_node) else "",
                                subtitle = listOfNotNull(stringResource(if (m.online) R.string.cluster_online else R.string.cluster_offline), m.ip).joinToString("  ·  "),
                                titleColor = if (m.online) MaterialTheme.colorScheme.onSurface else Health.critical,
                            )
                        }
                    }
                }

                val services = ha.filter { it.type == "service" }
                if (services.isNotEmpty()) {
                    Group(stringResource(R.string.cluster_ha), footer = stringResource(R.string.cluster_ha_footer)) {
                        ha.filter { it.type == "master" || it.type == "quorum" }.forEach { e ->
                            GroupRow(title = stringResource(if (e.type == "master") R.string.cluster_ha_manager else R.string.cluster_quorum), subtitle = e.status)
                        }
                        services.forEach { e ->
                            GroupRow(
                                title = e.sid,
                                subtitle = listOf(e.state, e.node).filter { it.isNotBlank() }.joinToString("  ·  "),
                                titleColor = if (e.state == "error" || e.state == "fence") Health.critical else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                if (replication.isNotEmpty()) {
                    Group(stringResource(R.string.cluster_replication, node)) {
                        replication.forEach { job ->
                            val failed = job.failCount > 0 || job.error != null
                            GroupRow(
                                title = stringResource(R.string.cluster_repl_job, job.id, job.target),
                                subtitle = listOfNotNull(
                                    if (job.disabled) stringResource(R.string.storage_job_disabled) else null,
                                    job.schedule.takeIf { it.isNotBlank() }?.let { stringResource(R.string.storage_job_schedule, it) },
                                    if (job.lastSync > 0) stringResource(R.string.cluster_repl_last, relativeTime(job.lastSync * 1000)) else stringResource(R.string.cluster_repl_never),
                                    if (job.failCount > 0) stringResource(R.string.cluster_repl_failed, job.failCount) else null,
                                    job.error,
                                ).joinToString("\n"),
                                titleColor = if (failed) Health.critical else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                ceph?.let { ce ->
                    Group(stringResource(R.string.cluster_ceph)) {
                        GroupRow(
                            title = stringResource(R.string.cluster_ceph_health),
                            subtitle = (listOf(ce.health) + ce.checks).joinToString("\n"),
                            titleColor = when (ce.health) {
                                "HEALTH_OK" -> MaterialTheme.colorScheme.onSurface
                                "HEALTH_WARN" -> Health.warn
                                else -> Health.critical
                            },
                        )
                        if (ce.totalBytes > 0) {
                            GroupRow(
                                title = stringResource(R.string.cluster_ceph_usage),
                                subtitle = stringResource(R.string.storage_usage, formatBytes(ce.usedBytes), formatBytes(ce.totalBytes), percent(ce.usedBytes.toDouble() / ce.totalBytes)),
                            )
                        }
                        if (ce.osds != null) {
                            GroupRow(
                                title = stringResource(R.string.cluster_ceph_osds),
                                subtitle = stringResource(R.string.cluster_ceph_osds_value, ce.osds, ce.osdsUp ?: 0, ce.osdsIn ?: 0),
                            )
                        }
                    }
                }
            }
        }
    }
}
