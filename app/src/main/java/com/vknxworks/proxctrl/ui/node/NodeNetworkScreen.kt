package com.vknxworks.proxctrl.ui.node

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.node.NetInterface
import com.vknxworks.proxctrl.data.node.NodeApi
import com.vknxworks.proxctrl.data.node.SdnVnet
import com.vknxworks.proxctrl.data.node.SdnZone
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.StatusDot
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Interfaces and bridges of the node, and the cluster's SDN zones and VNets. Read-only. */
@Composable
fun NodeNetworkScreen(node: String) {
    val context = LocalContext.current
    val c = context.container
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client

    var interfaces by remember { mutableStateOf<List<NetInterface>?>(null) }
    var zones by remember { mutableStateOf<List<SdnZone>?>(null) }
    var vnets by remember { mutableStateOf<List<SdnVnet>?>(null) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(server?.id, node, reload) {
        val cl = client ?: return@LaunchedEffect
        coroutineScope {
            val net = async { runCatching { NodeApi.network(cl, node) } }
            // SDN only lists what the user may see; no SDN at all is an empty list, not an error.
            val z = async { runCatching { NodeApi.sdnZones(cl) }.getOrNull() }
            val v = async { runCatching { NodeApi.sdnVnets(cl) }.getOrNull() }
            net.await().onSuccess { interfaces = it; error = null }.onFailure { e -> error = e as? PveError }
            zones = z.await()
            vnets = v.await()
        }
        refreshing = false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.node_network_title, node))
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reload++ },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                if (access.error != null) ConnectionBanner()
                val list = interfaces
                val err = error
                when {
                    list == null && err == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list == null && err != null -> Text(
                        errorMessage(context, err, server?.host.orEmpty(), server?.auth),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(Gutter),
                    )
                    list != null -> Group(stringResource(R.string.node_network_interfaces), footer = stringResource(R.string.node_network_footer)) {
                        if (list.isEmpty()) {
                            Text(
                                stringResource(R.string.node_network_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        list.forEach { InterfaceRow(it) }
                    }
                }

                val z = zones.orEmpty()
                val v = vnets.orEmpty()
                if (z.isNotEmpty() || v.isNotEmpty()) {
                    Group(stringResource(R.string.node_sdn_zones)) {
                        if (z.isEmpty()) EmptyLine(stringResource(R.string.node_sdn_none))
                        z.forEach { zone ->
                            val vnetCount = v.count { it.zone == zone.zone }
                            DetailRow(
                                title = zone.zone,
                                lines = listOfNotNull(
                                    stringResource(R.string.node_sdn_zone_detail, zone.type.ifBlank { "?" }, vnetCount),
                                    zone.bridge?.let { stringResource(R.string.node_sdn_bridge, it) },
                                    if (zone.pending) stringResource(R.string.node_sdn_pending) else null,
                                ),
                            )
                        }
                    }
                    Group(stringResource(R.string.node_sdn_vnets)) {
                        if (v.isEmpty()) EmptyLine(stringResource(R.string.node_sdn_none))
                        v.forEach { vnet ->
                            DetailRow(
                                title = vnet.vnet + (vnet.alias?.takeIf { it.isNotBlank() }?.let { "  ($it)" } ?: ""),
                                lines = listOfNotNull(
                                    stringResource(R.string.node_sdn_vnet_zone, vnet.zone),
                                    vnet.tag?.let { stringResource(R.string.node_sdn_vnet_tag, it) },
                                    if (vnet.vlanAware) stringResource(R.string.node_net_vlan_aware) else null,
                                    if (vnet.pending) stringResource(R.string.node_sdn_pending) else null,
                                ),
                            )
                        }
                    }
                } else if (zones != null || vnets != null) {
                    Group(stringResource(R.string.node_sdn_title)) { EmptyLine(stringResource(R.string.node_sdn_none)) }
                }
            }
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun DetailRow(title: String, lines: List<String>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun InterfaceRow(i: NetInterface) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        StatusDot(if (i.active) "running" else "stopped", modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(i.iface, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(typeLabel(i.type), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val lines = listOfNotNull(
                i.cidr?.let { a -> i.gateway?.let { stringResource(R.string.node_net_address_gw, a, it) } ?: a },
                i.cidr6?.let { a -> i.gateway6?.let { stringResource(R.string.node_net_address_gw, a, it) } ?: a },
                i.ports?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.node_net_ports, it) },
                i.vlanRawDevice?.let { dev -> stringResource(R.string.node_net_vlan_on, i.vlanId ?: "?", dev) },
                if (i.vlanAware) stringResource(R.string.node_net_vlan_aware) else null,
                i.mtu?.let { stringResource(R.string.node_net_mtu, it) },
                when {
                    !i.exists && i.type == "eth" -> stringResource(R.string.node_net_missing)
                    !i.active -> stringResource(R.string.node_net_inactive)
                    !i.autostart -> stringResource(R.string.node_net_no_autostart)
                    else -> null
                },
            )
            lines.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            i.comments?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun typeLabel(type: String): String = when (type) {
    "bridge" -> stringResource(R.string.node_net_type_bridge)
    "bond" -> stringResource(R.string.node_net_type_bond)
    "eth" -> stringResource(R.string.node_net_type_eth)
    "vlan" -> stringResource(R.string.node_net_type_vlan)
    "OVSBridge", "OVSBond", "OVSPort", "OVSIntPort" -> stringResource(R.string.node_net_type_ovs, type.removePrefix("OVS"))
    else -> type
}
