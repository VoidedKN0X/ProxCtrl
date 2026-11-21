package com.vknxworks.proxctrl.ui.node

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
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
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.node.PciApi
import com.vknxworks.proxctrl.data.node.PciDevice
import com.vknxworks.proxctrl.data.node.PciOverview
import com.vknxworks.proxctrl.data.node.PciUse
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.EmptyState
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.nav.GuestRoute
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import com.vknxworks.proxctrl.ui.theme.Gutter

/**
 * Graphics cards and other PCI devices of the node and which guest each is given to: whole devices
 * passed through to VMs, and the containers that are handed host device files. Read-only.
 */
@Composable
fun NodePciScreen(node: String) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val guests by c.guests.state.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client

    var overview by remember { mutableStateOf<PciOverview?>(null) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(server?.id, node, reload) {
        val cl = client ?: return@LaunchedEffect
        try {
            if (guests.guests.isEmpty()) runCatching { c.guests.refresh() }
            overview = PciApi.overview(cl, node, c.guests.state.value.guests)
            error = null
        } catch (e: PveError) {
            error = e
        }
        refreshing = false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.pci_title, node))
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; reload++ }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                if (access.error != null) ConnectionBanner()
                val o = overview
                val err = error
                when {
                    o == null && err == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    o == null && err != null -> Text(
                        if (err is PveError.Forbidden) stringResource(R.string.pci_forbidden) else errorMessage(context, err, server?.host.orEmpty(), server?.auth),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(Gutter),
                    )
                    o != null -> {
                        val gpus = o.devices.filter { it.isGpu }
                        val others = o.devices.filter { !it.isGpu && o.uses[it.id] != null }
                        if (gpus.isEmpty() && others.isEmpty() && o.unmatched.isEmpty() && o.containers.isEmpty()) {
                            EmptyState(
                                icon = Icons.Outlined.Memory,
                                title = stringResource(R.string.pci_empty),
                                body = stringResource(R.string.pci_empty_hint),
                                modifier = Modifier.heightIn(min = 320.dp),
                            )
                        }
                        if (gpus.isNotEmpty()) {
                            Group(stringResource(R.string.pci_gpus), footer = stringResource(R.string.pci_gpus_footer)) {
                                gpus.forEach { dev -> DeviceRows(dev, o.uses[dev.id].orEmpty(), gpu = true) { vmid -> nav.navigate(GuestRoute(node, GuestType.QEMU.api, vmid)) } }
                            }
                        }
                        if (others.isNotEmpty()) {
                            Group(stringResource(R.string.pci_others)) {
                                others.forEach { dev -> DeviceRows(dev, o.uses[dev.id].orEmpty(), gpu = false) { vmid -> nav.navigate(GuestRoute(node, GuestType.QEMU.api, vmid)) } }
                            }
                        }
                        if (o.unmatched.isNotEmpty()) {
                            Group(stringResource(R.string.pci_unmatched), footer = stringResource(R.string.pci_unmatched_footer)) {
                                o.unmatched.forEach { use ->
                                    GroupRow(
                                        title = guestLabel(use.vmid, use.name),
                                        subtitle = "${use.key}: ${use.raw}",
                                        showChevron = true,
                                        onClick = { nav.navigate(GuestRoute(node, GuestType.QEMU.api, use.vmid)) },
                                    )
                                }
                            }
                        }
                        if (o.containers.isNotEmpty()) {
                            Group(stringResource(R.string.pci_containers), footer = stringResource(R.string.pci_containers_footer)) {
                                o.containers.forEach { ct ->
                                    GroupRow(
                                        title = guestLabel(ct.vmid, ct.name) + if (ct.running) "" else "  ·  " + stringResource(R.string.status_stopped),
                                        subtitle = ct.paths.joinToString(", "),
                                        showChevron = true,
                                        onClick = { nav.navigate(GuestRoute(node, GuestType.LXC.api, ct.vmid)) },
                                    )
                                }
                            }
                        }
                        if (o.unreadable > 0) {
                            Text(
                                stringResource(R.string.pci_unreadable, o.unreadable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun guestLabel(vmid: Int, name: String): String = listOf(vmid.toString(), name).filter { it.isNotBlank() }.joinToString("  ")

/** A device and, under it, each VM it is given to (or that it's with the host). */
@Composable
private fun DeviceRows(dev: PciDevice, uses: List<PciUse>, gpu: Boolean, onGuest: (Int) -> Unit) {
    val where = listOfNotNull(
        dev.id,
        if (dev.iommuGroup >= 0) stringResource(R.string.pci_iommu_group, dev.iommuGroup) else stringResource(R.string.pci_no_iommu),
    ).joinToString("  ·  ")
    GroupRow(
        title = dev.label,
        subtitle = if (uses.isEmpty() && gpu) where + "\n" + stringResource(R.string.pci_with_host) else where,
    )
    uses.forEach { use ->
        GroupRow(
            title = stringResource(R.string.pci_given_to, guestLabel(use.vmid, use.name)),
            subtitle = listOfNotNull(
                use.key,
                use.mapping?.let { stringResource(R.string.pci_mapping, it) },
                stringResource(if (use.running) R.string.status_running else R.string.status_stopped),
            ).joinToString("  ·  "),
            titleColor = MaterialTheme.colorScheme.primary,
            showChevron = true,
            onClick = { onGuest(use.vmid) },
        )
    }
}
