package com.vknxworks.proxctrl.ui.guests

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.guests.CreateApi
import com.vknxworks.proxctrl.data.guests.Ipv4Mode
import com.vknxworks.proxctrl.data.guests.Ipv6Mode
import com.vknxworks.proxctrl.data.guests.LxcSpec
import com.vknxworks.proxctrl.data.guests.NicSpec
import com.vknxworks.proxctrl.data.storage.ContentItem
import com.vknxworks.proxctrl.data.storage.StorageApi
import com.vknxworks.proxctrl.data.storage.StorageInfo
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.SecureScreen
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import com.vknxworks.proxctrl.ui.storage.StorageTemplatesRoute
import com.vknxworks.proxctrl.ui.tasks.TaskDetailRoute
import com.vknxworks.proxctrl.ui.theme.CtrlColors
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

/**
 * New container: template, ID and hostname, root password and/or SSH key, unprivileged and nesting,
 * root disk, CPU and memory, network and DNS, then POST /nodes/{node}/lxc and the task log.
 * The whole wizard is kept out of screenshots because it holds the root password.
 */
@Composable
fun CreateLxcScreen() {
    SecureScreen()
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val perms = access.permissions

    // Saveable, so leaving to download a template and coming back keeps what was entered.
    var step by rememberSaveable { mutableIntStateOf(0) }
    var nodes by remember { mutableStateOf<List<String>>(emptyList()) }
    var node by rememberSaveable { mutableStateOf("") }
    var tmplStorage by rememberSaveable { mutableStateOf<String?>(null) }
    var template by rememberSaveable { mutableStateOf<String?>(null) }
    var vmid by rememberSaveable { mutableStateOf("") }
    var idOk by remember { mutableStateOf(false) }
    var hostname by rememberSaveable { mutableStateOf("") }
    var unprivileged by rememberSaveable { mutableStateOf(true) }
    var nesting by rememberSaveable { mutableStateOf(true) }
    // The password is never put in saved state.
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var sshKeys by rememberSaveable { mutableStateOf("") }
    var diskStorage by rememberSaveable { mutableStateOf<String?>(null) }
    var diskGiB by rememberSaveable { mutableStateOf("8") }
    var cores by rememberSaveable { mutableStateOf("1") }
    var memory by rememberSaveable { mutableStateOf("512") }
    var swap by rememberSaveable { mutableStateOf("512") }
    var bridge by rememberSaveable { mutableStateOf("vmbr0") }
    var vlan by rememberSaveable { mutableStateOf("") }
    var firewall by rememberSaveable { mutableStateOf(true) }
    var ipv4 by rememberSaveable { mutableStateOf(Ipv4Mode.DHCP) }
    var ipv4Cidr by rememberSaveable { mutableStateOf("") }
    var ipv4Gw by rememberSaveable { mutableStateOf("") }
    var ipv6 by rememberSaveable { mutableStateOf(Ipv6Mode.NONE) }
    var ipv6Cidr by rememberSaveable { mutableStateOf("") }
    var ipv6Gw by rememberSaveable { mutableStateOf("") }
    var dnsDomain by rememberSaveable { mutableStateOf("") }
    var dnsServers by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf(true) }

    var tmplStorages by remember { mutableStateOf<List<StorageInfo>?>(null) }
    var templates by remember { mutableStateOf<List<ContentItem>?>(null) }
    var templatesError by remember { mutableStateOf<PveError?>(null) }
    var templatesReload by remember { mutableIntStateOf(0) }
    var diskStorages by remember { mutableStateOf<List<StorageInfo>?>(null) }
    var bridges by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<PveError?>(null) }

    LaunchedEffect(server?.id) {
        val cl = client ?: return@LaunchedEffect
        nodes = onlineNodes(cl)
        if (node.isBlank() || node !in nodes) node = nodes.firstOrNull().orEmpty()
    }
    LaunchedEffect(node, server?.id) {
        val cl = client ?: return@LaunchedEffect
        if (node.isBlank()) return@LaunchedEffect
        tmplStorages = runCatching { CreateApi.storagesFor(cl, node, "vztmpl") }.getOrDefault(emptyList())
        diskStorages = runCatching { CreateApi.storagesFor(cl, node, "rootdir") }.getOrDefault(emptyList())
        bridges = runCatching { CreateApi.bridges(cl, node) }.getOrDefault(emptyList())
        if (tmplStorage == null || tmplStorages?.none { it.id == tmplStorage } == true) tmplStorage = tmplStorages?.firstOrNull()?.id
        if (diskStorage == null || diskStorages?.none { it.id == diskStorage } == true) {
            diskStorage = diskStorages?.firstOrNull { c.sessions.canWrite("/storage/${it.id}", CreateApi.PRIV_SPACE) }?.id ?: diskStorages?.firstOrNull()?.id
        }
        if (bridges.isNotEmpty() && bridge !in bridges) bridge = bridges.first()
    }
    LaunchedEffect(node, tmplStorage, templatesReload) {
        val cl = client ?: return@LaunchedEffect
        val s = tmplStorage ?: return@LaunchedEffect
        templates = null
        try {
            templates = CreateApi.volumes(cl, node, s, "vztmpl")
            templatesError = null
        } catch (e: PveError) {
            templates = emptyList()
            templatesError = e
        }
    }

    val steps = listOf(
        stringResource(R.string.create_step_template),
        stringResource(R.string.create_step_general),
        stringResource(R.string.create_step_login),
        stringResource(R.string.create_step_disk),
        stringResource(R.string.create_step_cpu_memory),
        stringResource(R.string.create_step_network),
        stringResource(R.string.create_step_dns),
        stringResource(R.string.create_step_confirm),
    )

    val passwordOk = password.isEmpty() || (password.length >= 5 && password == password2)
    val keysOk = sshKeys.lines().map { it.trim() }.filter { it.isNotEmpty() }.all { it.startsWith("ssh-") || it.startsWith("ecdsa-") || it.startsWith("sk-") }
    val loginOk = passwordOk && keysOk && (password.isNotEmpty() || sshKeys.isNotBlank())
    val diskOk = diskStorage != null && (diskGiB.toIntOrNull() ?: 0) in 1..65536
    val cpuOk = (cores.toIntOrNull() ?: 0) in 1..512 && (memory.toLongOrNull() ?: 0) >= 16 && swap.toLongOrNull() != null
    val netOk = CreateChecks.bridge(bridge) && CreateChecks.vlan(vlan) &&
        (ipv4 != Ipv4Mode.STATIC || (CreateChecks.ipv4Cidr(ipv4Cidr) && (ipv4Gw.isBlank() || CreateChecks.ipv4(ipv4Gw)))) &&
        (ipv6 != Ipv6Mode.STATIC || (CreateChecks.ipv6Cidr(ipv6Cidr) && (ipv6Gw.isBlank() || CreateChecks.ipv6(ipv6Gw))))
    val dnsOk = dnsDomain.isBlank() || CreateChecks.dnsName(dnsDomain)

    fun spec(): LxcSpec? {
        val t = template ?: return null
        val id = vmid.toIntOrNull() ?: return null
        val ds = diskStorage ?: return null
        return LxcSpec(
            node = node, vmid = id, hostname = hostname, template = t, password = password, sshKeys = sshKeys,
            unprivileged = unprivileged, nesting = nesting, storage = ds, diskGiB = diskGiB.toIntOrNull() ?: 8,
            cores = cores.toIntOrNull() ?: 1, memoryMiB = memory.toLongOrNull() ?: 512, swapMiB = swap.toLongOrNull() ?: 0,
            nic = NicSpec(bridge.trim(), vlan.toIntOrNull(), firewall),
            ipv4 = ipv4, ipv4Cidr = ipv4Cidr, ipv4Gateway = ipv4Gw, ipv6 = ipv6, ipv6Cidr = ipv6Cidr, ipv6Gateway = ipv6Gw,
            dnsDomain = dnsDomain, dnsServers = dnsServers, start = start,
        )
    }

    val canNext = when (step) {
        0 -> template != null && node.isNotBlank()
        1 -> idOk && CreateChecks.dnsName(hostname)
        2 -> loginOk
        3 -> diskOk
        4 -> cpuOk
        5 -> netOk
        6 -> dnsOk
        else -> spec() != null && server?.readOnly == false
    }

    WizardScaffold(
        title = stringResource(R.string.create_lxc),
        steps = steps,
        step = step,
        onStep = { step = it; createError = null },
        canNext = canNext,
        busy = busy,
        finishLabel = stringResource(R.string.create_button),
        onFinish = {
            val s = spec() ?: return@WizardScaffold
            val cl = client ?: return@WizardScaffold
            busy = true
            createError = null
            scope.launch {
                try {
                    val upid = CreateApi.createLxc(cl, s)
                    c.appScope.launch { c.guests.refresh() }
                    if (!upid.isNullOrBlank() && upid.startsWith("UPID:")) {
                        nav.nav.navigate(TaskDetailRoute(upid)) { popUpTo<CreateGuestRoute> { inclusive = true } }
                    } else {
                        nav.back()
                    }
                } catch (e: PveError) {
                    createError = e
                } finally {
                    busy = false
                }
            }
        },
    ) {
        if (access.error != null) ConnectionBanner()
        when (step) {
            0 -> {
                NodeChips(nodes, node) { node = it; template = null }
                WizardLabel(stringResource(R.string.create_template_storage))
                StorageChips(tmplStorages, tmplStorage, allowed = { true }) { tmplStorage = it; template = null }
                val ts = tmplStorage
                val canDownload = ts != null && c.sessions.canWrite("/storage/$ts", StorageApi.PRIV_TEMPLATE)
                Row(Modifier.padding(horizontal = Gutter), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { ts?.let { nav.navigate(StorageTemplatesRoute(node, it)) } }, enabled = canDownload) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.create_download_templates))
                    }
                    TextButton(onClick = { templatesReload++ }) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.create_refresh))
                    }
                }
                if (ts != null && !canDownload) WizardHint(stringResource(R.string.create_download_not_allowed))
                templatesError?.let { WizardHint(errorMessage(context, it, server?.host.orEmpty(), server?.auth), error = true) }
                val list = templates
                when {
                    list == null -> WizardHint(stringResource(R.string.create_loading))
                    list.isEmpty() && templatesError == null -> WizardHint(stringResource(R.string.create_no_ct_templates))
                    list.isNotEmpty() -> Group {
                        list.forEach { t ->
                            val sel = t.volid == template
                            GroupRow(
                                title = t.name,
                                subtitle = formatBytes(t.size).takeIf { t.size > 0 },
                                icon = if (sel) Icons.Outlined.CheckCircle else Icons.Outlined.Layers,
                                iconTint = if (sel) CtrlColors.AccentText else CtrlColors.Muted,
                                titleColor = if (sel) CtrlColors.AccentText else CtrlColors.Text,
                                onClick = { template = t.volid },
                            )
                        }
                    }
                }
            }
            1 -> {
                IdField(client, vmid, { vmid = it }, { idOk = it })
                WizardField(
                    value = hostname,
                    onValueChange = { hostname = it.trim().take(63) },
                    label = stringResource(R.string.clone_hostname),
                    isError = hostname.isNotEmpty() && !CreateChecks.dnsName(hostname),
                    supporting = stringResource(R.string.clone_name_hint),
                    keyboardType = KeyboardType.Uri,
                )
                WizardSwitch(
                    stringResource(R.string.create_unprivileged),
                    stringResource(if (unprivileged) R.string.create_unprivileged_hint else R.string.create_privileged_hint),
                    unprivileged,
                ) { unprivileged = it; if (!it) nesting = false }
                WizardSwitch(
                    stringResource(R.string.create_nesting),
                    stringResource(if (unprivileged) R.string.create_nesting_hint else R.string.create_nesting_privileged),
                    nesting,
                    enabled = unprivileged,
                ) { nesting = it }
            }
            2 -> {
                WizardHint(stringResource(R.string.create_login_hint))
                WizardField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.create_root_password),
                    isError = password.isNotEmpty() && password.length < 5,
                    supporting = stringResource(R.string.create_password_min),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardType = KeyboardType.Password,
                )
                WizardField(
                    value = password2,
                    onValueChange = { password2 = it },
                    label = stringResource(R.string.create_root_password_again),
                    isError = password2.isNotEmpty() && password2 != password,
                    supporting = if (password2.isNotEmpty() && password2 != password) stringResource(R.string.create_password_mismatch) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardType = KeyboardType.Password,
                )
                WizardField(
                    value = sshKeys,
                    onValueChange = { sshKeys = it },
                    label = stringResource(R.string.create_ssh_keys),
                    isError = !keysOk,
                    supporting = stringResource(R.string.create_ssh_keys_hint),
                    singleLine = false,
                    minLines = 3,
                )
            }
            3 -> {
                WizardLabel(stringResource(R.string.create_disk_storage))
                StorageChips(diskStorages, diskStorage, allowed = { c.sessions.canWrite("/storage/$it", CreateApi.PRIV_SPACE) }) { diskStorage = it }
                WizardField(
                    value = diskGiB,
                    onValueChange = { diskGiB = it.filter(Char::isDigit).take(5) },
                    label = stringResource(R.string.create_disk_size),
                    isError = (diskGiB.toIntOrNull() ?: 0) !in 1..65536,
                    number = true,
                )
            }
            4 -> {
                WizardField(cores, { cores = it.filter(Char::isDigit).take(3) }, stringResource(R.string.res_cores_label), isError = (cores.toIntOrNull() ?: 0) !in 1..512, number = true)
                WizardField(
                    memory, { memory = it.filter(Char::isDigit).take(8) }, stringResource(R.string.res_memory_mib),
                    isError = (memory.toLongOrNull() ?: 0) < 16, supporting = memory.toLongOrNull()?.let(::formatMiB), number = true,
                )
                WizardChips(listOf(512L, 1024L, 2048L, 4096L, 8192L).map { it.toString() to formatMiB(it) }, memory, { memory = it })
                WizardField(
                    swap, { swap = it.filter(Char::isDigit).take(8) }, stringResource(R.string.res_swap_mib),
                    isError = swap.toLongOrNull() == null, supporting = swap.toLongOrNull()?.let(::formatMiB), number = true,
                )
            }
            5 -> {
                NetworkFields(bridges, bridge, { bridge = it }, vlan, { vlan = it }, firewall, { firewall = it })
                WizardLabel(stringResource(R.string.create_ipv4))
                WizardChips(
                    listOf(Ipv4Mode.DHCP to stringResource(R.string.create_ip_dhcp), Ipv4Mode.STATIC to stringResource(R.string.create_ip_static), Ipv4Mode.NONE to stringResource(R.string.create_ip_none)),
                    ipv4, { ipv4 = it },
                )
                if (ipv4 == Ipv4Mode.STATIC) {
                    WizardField(ipv4Cidr, { ipv4Cidr = it.trim() }, stringResource(R.string.create_ipv4_cidr), isError = ipv4Cidr.isNotEmpty() && !CreateChecks.ipv4Cidr(ipv4Cidr), placeholder = "192.168.1.50/24", keyboardType = KeyboardType.Uri)
                    WizardField(ipv4Gw, { ipv4Gw = it.trim() }, stringResource(R.string.create_gateway), isError = ipv4Gw.isNotEmpty() && !CreateChecks.ipv4(ipv4Gw), placeholder = "192.168.1.1", keyboardType = KeyboardType.Uri)
                }
                WizardLabel(stringResource(R.string.create_ipv6))
                WizardChips(
                    listOf(
                        Ipv6Mode.NONE to stringResource(R.string.create_ip_none),
                        Ipv6Mode.AUTO to stringResource(R.string.create_ip_slaac),
                        Ipv6Mode.DHCP to stringResource(R.string.create_ip_dhcp),
                        Ipv6Mode.STATIC to stringResource(R.string.create_ip_static),
                    ),
                    ipv6, { ipv6 = it },
                )
                if (ipv6 == Ipv6Mode.STATIC) {
                    WizardField(ipv6Cidr, { ipv6Cidr = it.trim() }, stringResource(R.string.create_ipv6_cidr), isError = ipv6Cidr.isNotEmpty() && !CreateChecks.ipv6Cidr(ipv6Cidr), placeholder = "2001:db8::50/64", keyboardType = KeyboardType.Uri)
                    WizardField(ipv6Gw, { ipv6Gw = it.trim() }, stringResource(R.string.create_gateway), isError = ipv6Gw.isNotEmpty() && !CreateChecks.ipv6(ipv6Gw), placeholder = "2001:db8::1", keyboardType = KeyboardType.Uri)
                }
            }
            6 -> {
                WizardHint(stringResource(R.string.create_dns_hint))
                WizardField(dnsDomain, { dnsDomain = it.trim() }, stringResource(R.string.create_dns_domain), isError = !dnsOk, keyboardType = KeyboardType.Uri)
                WizardField(dnsServers, { dnsServers = it }, stringResource(R.string.create_dns_servers), supporting = stringResource(R.string.create_dns_servers_hint), keyboardType = KeyboardType.Uri)
            }
            else -> {
                val s = spec()
                if (s != null) {
                    SummaryCard {
                        SummaryRow(stringResource(R.string.create_id), "${s.vmid}")
                        SummaryRow(stringResource(R.string.clone_hostname), s.hostname)
                        if (nodes.size > 1) SummaryRow(stringResource(R.string.create_node), s.node)
                        SummaryRow(stringResource(R.string.create_step_template), s.template.substringAfterLast('/'))
                        SummaryRow(stringResource(R.string.create_type), stringResource(if (s.unprivileged) R.string.create_unprivileged else R.string.create_privileged) + if (s.nesting) " · " + stringResource(R.string.create_nesting) else "")
                        SummaryRow(stringResource(R.string.create_step_login), listOfNotNull(
                            if (s.password.isNotEmpty()) stringResource(R.string.create_root_password) else null,
                            if (s.sshKeys.isNotBlank()) stringResource(R.string.create_ssh_keys) else null,
                        ).joinToString(" + "))
                        SummaryRow(stringResource(R.string.create_step_disk), "${s.storage} · ${s.diskGiB} GiB")
                        SummaryRow(stringResource(R.string.create_step_cpu_memory), stringResource(R.string.create_summary_cpu_mem_swap, s.cores, formatMiB(s.memoryMiB), formatMiB(s.swapMiB)))
                        SummaryRow(stringResource(R.string.create_step_network), CreateApi.lxcParams(s)["net0"].orEmpty())
                        if (s.dnsDomain.isNotBlank() || s.dnsServers.isNotBlank()) {
                            SummaryRow(stringResource(R.string.create_step_dns), listOf(s.dnsDomain, s.dnsServers).filter { it.isNotBlank() }.joinToString(" · "))
                        }
                    }
                    WizardSwitch(stringResource(R.string.create_start_after), null, start) { start = it }
                    MissingPrivsCard(CreateApi.missingForLxc(perms, server?.authId.orEmpty(), s))
                }
                createError?.let { e ->
                    CreateErrorCard(errorMessage(context, e, server?.host.orEmpty(), server?.auth), CreateChecks.isForbidden(e))
                }
            }
        }
    }
}

/** Bridge (from the node's bridges, or typed), VLAN tag and firewall: the same for containers and VMs. */
@Composable
internal fun NetworkFields(
    bridges: List<String>,
    bridge: String,
    onBridge: (String) -> Unit,
    vlan: String,
    onVlan: (String) -> Unit,
    firewall: Boolean,
    onFirewall: (Boolean) -> Unit,
) {
    WizardLabel(stringResource(R.string.create_bridge))
    if (bridges.isNotEmpty()) {
        WizardChips(bridges.map { it to it }, bridge, onBridge)
    } else {
        WizardField(bridge, { onBridge(it.trim().take(15)) }, stringResource(R.string.create_bridge), isError = !CreateChecks.bridge(bridge), supporting = stringResource(R.string.create_bridge_hint))
    }
    WizardField(
        vlan, { onVlan(it.filter(Char::isDigit).take(4)) }, stringResource(R.string.create_vlan),
        isError = !CreateChecks.vlan(vlan), supporting = stringResource(R.string.create_vlan_hint), number = true,
    )
    WizardSwitch(stringResource(R.string.create_firewall), stringResource(R.string.create_firewall_hint), firewall, onChange = onFirewall)
}
