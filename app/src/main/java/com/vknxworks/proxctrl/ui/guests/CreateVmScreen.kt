package com.vknxworks.proxctrl.ui.guests

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.guests.CreateApi
import com.vknxworks.proxctrl.data.guests.NicSpec
import com.vknxworks.proxctrl.data.guests.VmSpec
import com.vknxworks.proxctrl.data.storage.ContentItem
import com.vknxworks.proxctrl.data.storage.StorageInfo
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import com.vknxworks.proxctrl.ui.tasks.TaskDetailRoute
import com.vknxworks.proxctrl.ui.theme.CtrlColors
import kotlinx.coroutines.launch

private val OS_TYPES = listOf("l26", "win11", "win10", "win8", "win7", "solaris", "other")
private val SCSI_HW = listOf("virtio-scsi-single", "virtio-scsi-pci", "lsi", "lsi53c810", "megasas", "pvscsi")
private val DISK_BUS = listOf("scsi", "virtio", "sata", "ide")
private val CPU_TYPES = listOf("x86-64-v2-AES", "x86-64-v3", "x86-64-v4", "host", "kvm64", "qemu64")
private val NIC_MODELS = listOf("virtio", "e1000", "e1000e", "rtl8139", "vmxnet3")

/**
 * New VM: ID, name and OS type, an ISO or none, BIOS and machine, the system disk, CPU, memory and
 * network, then POST /nodes/{node}/qemu and the task log. The defaults are the web UI's.
 */
@Composable
fun CreateVmScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val perms = access.permissions

    var step by rememberSaveable { mutableIntStateOf(0) }
    var nodes by remember { mutableStateOf<List<String>>(emptyList()) }
    var node by rememberSaveable { mutableStateOf("") }
    var vmid by rememberSaveable { mutableStateOf("") }
    var idOk by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var osType by rememberSaveable { mutableStateOf("l26") }
    var isoStorage by rememberSaveable { mutableStateOf<String?>(null) }
    var iso by rememberSaveable { mutableStateOf<String?>(null) }
    var ovmf by rememberSaveable { mutableStateOf(false) }
    var efiStorage by rememberSaveable { mutableStateOf<String?>(null) }
    var preEnrolled by rememberSaveable { mutableStateOf(true) }
    var tpm by rememberSaveable { mutableStateOf(false) }
    var q35 by rememberSaveable { mutableStateOf(true) }
    var scsiHw by rememberSaveable { mutableStateOf("virtio-scsi-single") }
    var diskBus by rememberSaveable { mutableStateOf("scsi") }
    var diskStorage by rememberSaveable { mutableStateOf<String?>(null) }
    var diskGiB by rememberSaveable { mutableStateOf("32") }
    var diskFormat by rememberSaveable { mutableStateOf("qcow2") }
    var sockets by rememberSaveable { mutableStateOf("1") }
    var cores by rememberSaveable { mutableStateOf("2") }
    var cpuType by rememberSaveable { mutableStateOf("x86-64-v2-AES") }
    var memory by rememberSaveable { mutableStateOf("2048") }
    var ballooning by rememberSaveable { mutableStateOf(true) }
    var balloonMin by rememberSaveable { mutableStateOf("") }
    var bridge by rememberSaveable { mutableStateOf("vmbr0") }
    var vlan by rememberSaveable { mutableStateOf("") }
    var firewall by rememberSaveable { mutableStateOf(true) }
    var nicModel by rememberSaveable { mutableStateOf("virtio") }
    var agent by rememberSaveable { mutableStateOf(true) }
    var start by rememberSaveable { mutableStateOf(false) }

    var isoStorages by remember { mutableStateOf<List<StorageInfo>?>(null) }
    var isos by remember { mutableStateOf<List<ContentItem>?>(null) }
    var isoError by remember { mutableStateOf<PveError?>(null) }
    var imageStorages by remember { mutableStateOf<List<StorageInfo>?>(null) }
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
        isoStorages = runCatching { CreateApi.storagesFor(cl, node, "iso") }.getOrDefault(emptyList())
        imageStorages = runCatching { CreateApi.storagesFor(cl, node, "images") }.getOrDefault(emptyList())
        bridges = runCatching { CreateApi.bridges(cl, node) }.getOrDefault(emptyList())
        if (isoStorage == null || isoStorages?.none { it.id == isoStorage } == true) isoStorage = isoStorages?.firstOrNull()?.id
        val usable = imageStorages?.firstOrNull { c.sessions.canWrite("/storage/${it.id}", CreateApi.PRIV_SPACE) } ?: imageStorages?.firstOrNull()
        if (diskStorage == null || imageStorages?.none { it.id == diskStorage } == true) diskStorage = usable?.id
        if (efiStorage == null || imageStorages?.none { it.id == efiStorage } == true) efiStorage = usable?.id
        if (bridges.isNotEmpty() && bridge !in bridges) bridge = bridges.first()
    }
    LaunchedEffect(node, isoStorage) {
        val cl = client ?: return@LaunchedEffect
        val s = isoStorage ?: return@LaunchedEffect
        isos = null
        try {
            isos = CreateApi.volumes(cl, node, s, "iso")
            isoError = null
        } catch (e: PveError) {
            isos = emptyList()
            isoError = e
        }
    }

    val steps = listOf(
        stringResource(R.string.create_step_general),
        stringResource(R.string.create_step_os),
        stringResource(R.string.create_step_system),
        stringResource(R.string.create_step_disk),
        stringResource(R.string.create_step_cpu),
        stringResource(R.string.create_step_memory),
        stringResource(R.string.create_step_network),
        stringResource(R.string.create_step_confirm),
    )

    val diskInfo = imageStorages?.firstOrNull { it.id == diskStorage }
    val diskIsFile = diskInfo?.let(CreateApi::isFileStorage) == true
    val mem = memory.toLongOrNull()
    val balloonMinVal = balloonMin.toLongOrNull()
    val systemOk = !ovmf || efiStorage != null
    val diskOk = diskStorage != null && (diskGiB.toIntOrNull() ?: 0) in 1..131072
    val cpuOk = (sockets.toIntOrNull() ?: 0) in 1..4 && (cores.toIntOrNull() ?: 0) in 1..512 && Regex("^[A-Za-z0-9_.+-]+$").matches(cpuType.trim())
    val memOk = mem != null && mem >= 16 && (!ballooning || balloonMin.isBlank() || (balloonMinVal != null && balloonMinVal in 1..mem))
    val netOk = CreateChecks.bridge(bridge) && CreateChecks.vlan(vlan)
    val nameOk = name.isBlank() || CreateChecks.dnsName(name)

    fun spec(): VmSpec? {
        val id = vmid.toIntOrNull() ?: return null
        val ds = diskStorage ?: return null
        return VmSpec(
            node = node, vmid = id, name = name, osType = osType, iso = iso,
            ovmf = ovmf, efiStorage = if (ovmf) efiStorage else null, preEnrolledKeys = preEnrolled, tpm = ovmf && tpm,
            q35 = q35, scsiHw = scsiHw, diskBus = diskBus, storage = ds, diskGiB = diskGiB.toIntOrNull() ?: 32,
            diskFormat = if (diskIsFile) diskFormat else null,
            sockets = sockets.toIntOrNull() ?: 1, cores = cores.toIntOrNull() ?: 1, cpuType = cpuType.trim(),
            memoryMiB = mem ?: 2048, ballooning = ballooning, balloonMinMiB = if (ballooning) balloonMinVal else null,
            nicModel = nicModel, nic = NicSpec(bridge.trim(), vlan.toIntOrNull(), firewall), agent = agent, start = start,
        )
    }

    val canNext = when (step) {
        0 -> idOk && nameOk && node.isNotBlank()
        1 -> true
        2 -> systemOk
        3 -> diskOk
        4 -> cpuOk
        5 -> memOk
        6 -> netOk
        else -> spec() != null && server?.readOnly == false
    }

    WizardScaffold(
        title = stringResource(R.string.create_vm),
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
                    val upid = CreateApi.createVm(cl, s, diskIsFile)
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
                NodeChips(nodes, node) { node = it; iso = null }
                IdField(client, vmid, { vmid = it }, { idOk = it })
                WizardField(
                    value = name,
                    onValueChange = { name = it.trim().take(63) },
                    label = stringResource(R.string.clone_name),
                    isError = !nameOk,
                    supporting = stringResource(R.string.clone_name_hint),
                    keyboardType = KeyboardType.Uri,
                )
            }
            1 -> {
                WizardLabel(stringResource(R.string.create_os_type))
                WizardChips(OS_TYPES.map { it to osLabel(it) }, osType, {
                    osType = it
                    // Windows 11 needs UEFI with Secure Boot keys and a TPM, as the web UI sets up.
                    if (it == "win11") { ovmf = true; tpm = true; preEnrolled = true }
                })
                WizardLabel(stringResource(R.string.create_iso))
                StorageChips(isoStorages, isoStorage, allowed = { true }) { isoStorage = it; iso = null }
                isoError?.let { WizardHint(errorMessage(context, it, server?.host.orEmpty(), server?.auth), error = true) }
                Group {
                    GroupRow(
                        title = stringResource(R.string.create_iso_none),
                        icon = if (iso == null) Icons.Outlined.CheckCircle else Icons.Outlined.Block,
                        iconTint = if (iso == null) CtrlColors.AccentText else CtrlColors.Muted,
                        titleColor = if (iso == null) CtrlColors.AccentText else CtrlColors.Text,
                        onClick = { iso = null },
                    )
                    isos.orEmpty().forEach { item ->
                        val sel = item.volid == iso
                        GroupRow(
                            title = item.name,
                            subtitle = formatBytes(item.size).takeIf { item.size > 0 },
                            icon = if (sel) Icons.Outlined.CheckCircle else Icons.Outlined.Album,
                            iconTint = if (sel) CtrlColors.AccentText else CtrlColors.Muted,
                            titleColor = if (sel) CtrlColors.AccentText else CtrlColors.Text,
                            onClick = { iso = item.volid },
                        )
                    }
                }
                if (isos == null && isoStorage != null) WizardHint(stringResource(R.string.create_loading))
                if (isos?.isEmpty() == true && isoError == null) WizardHint(stringResource(R.string.create_no_isos))
            }
            2 -> {
                WizardLabel(stringResource(R.string.create_bios))
                WizardChips(listOf(false to stringResource(R.string.create_bios_seabios), true to stringResource(R.string.create_bios_ovmf)), ovmf, { ovmf = it })
                if (ovmf) {
                    WizardLabel(stringResource(R.string.create_efi_storage))
                    StorageChips(imageStorages, efiStorage, allowed = { c.sessions.canWrite("/storage/$it", CreateApi.PRIV_SPACE) }) { efiStorage = it }
                    WizardSwitch(stringResource(R.string.create_secure_boot), stringResource(R.string.create_secure_boot_hint), preEnrolled) { preEnrolled = it }
                    WizardSwitch(stringResource(R.string.create_tpm), stringResource(R.string.create_tpm_hint), tpm) { tpm = it }
                }
                WizardLabel(stringResource(R.string.create_machine))
                WizardChips(listOf(true to "q35", false to "i440fx"), q35, { q35 = it })
                WizardLabel(stringResource(R.string.create_scsi_hw))
                WizardChips(SCSI_HW.map { it to scsiLabel(it) }, scsiHw, { scsiHw = it })
                WizardSwitch(stringResource(R.string.create_agent), stringResource(R.string.create_agent_hint), agent) { agent = it }
            }
            3 -> {
                WizardLabel(stringResource(R.string.create_disk_bus))
                WizardChips(DISK_BUS.map { it to busLabel(it) }, diskBus, { diskBus = it })
                WizardLabel(stringResource(R.string.create_disk_storage))
                StorageChips(imageStorages, diskStorage, allowed = { c.sessions.canWrite("/storage/$it", CreateApi.PRIV_SPACE) }) { diskStorage = it }
                WizardField(
                    diskGiB, { diskGiB = it.filter(Char::isDigit).take(6) }, stringResource(R.string.create_disk_size),
                    isError = (diskGiB.toIntOrNull() ?: 0) !in 1..131072, number = true,
                )
                if (diskIsFile) {
                    WizardLabel(stringResource(R.string.create_disk_format))
                    WizardChips(listOf("qcow2" to "QEMU (qcow2)", "raw" to "Raw", "vmdk" to "VMware (vmdk)"), diskFormat, { diskFormat = it })
                } else if (diskInfo != null) {
                    WizardHint(stringResource(R.string.create_disk_format_fixed, diskInfo.type))
                }
            }
            4 -> {
                WizardField(sockets, { sockets = it.filter(Char::isDigit).take(1) }, stringResource(R.string.create_sockets), isError = (sockets.toIntOrNull() ?: 0) !in 1..4, number = true)
                WizardField(cores, { cores = it.filter(Char::isDigit).take(3) }, stringResource(R.string.res_cores_per_socket), isError = (cores.toIntOrNull() ?: 0) !in 1..512, number = true)
                WizardLabel(stringResource(R.string.create_cpu_type))
                WizardChips(CPU_TYPES.map { it to it }, cpuType, { cpuType = it })
                WizardField(cpuType, { cpuType = it.trim().take(64) }, stringResource(R.string.create_cpu_type_custom), isError = !cpuOk && cpuType.isNotEmpty(), supporting = stringResource(R.string.create_cpu_type_hint))
            }
            5 -> {
                WizardField(memory, { memory = it.filter(Char::isDigit).take(8) }, stringResource(R.string.res_memory_mib), isError = (mem ?: 0) < 16, supporting = mem?.let(::formatMiB), number = true)
                WizardChips(listOf(1024L, 2048L, 4096L, 8192L, 16384L).map { it.toString() to formatMiB(it) }, memory, { memory = it })
                WizardSwitch(stringResource(R.string.create_ballooning), stringResource(R.string.create_ballooning_hint), ballooning) { ballooning = it }
                if (ballooning) {
                    WizardField(
                        balloonMin, { balloonMin = it.filter(Char::isDigit).take(8) }, stringResource(R.string.create_balloon_min),
                        isError = balloonMin.isNotEmpty() && (balloonMinVal == null || mem == null || balloonMinVal !in 1..mem),
                        supporting = stringResource(R.string.create_balloon_min_hint), number = true,
                    )
                }
            }
            6 -> {
                NetworkFields(bridges, bridge, { bridge = it }, vlan, { vlan = it }, firewall, { firewall = it })
                WizardLabel(stringResource(R.string.create_nic_model))
                WizardChips(NIC_MODELS.map { it to nicLabel(it) }, nicModel, { nicModel = it })
            }
            else -> {
                val s = spec()
                if (s != null) {
                    SummaryCard {
                        SummaryRow(stringResource(R.string.create_id), "${s.vmid}")
                        if (s.name.isNotBlank()) SummaryRow(stringResource(R.string.clone_name), s.name)
                        if (nodes.size > 1) SummaryRow(stringResource(R.string.create_node), s.node)
                        SummaryRow(stringResource(R.string.create_os_type), osLabel(s.osType))
                        SummaryRow(stringResource(R.string.create_iso), s.iso?.substringAfterLast('/') ?: stringResource(R.string.create_iso_none))
                        SummaryRow(
                            stringResource(R.string.create_step_system),
                            listOfNotNull(
                                if (s.ovmf) stringResource(R.string.create_bios_ovmf) else stringResource(R.string.create_bios_seabios),
                                if (s.q35) "q35" else "i440fx",
                                scsiLabel(s.scsiHw),
                                if (s.tpm) stringResource(R.string.create_tpm) else null,
                                if (s.agent) stringResource(R.string.create_agent) else null,
                            ).joinToString(" · "),
                        )
                        SummaryRow(stringResource(R.string.create_step_disk), listOfNotNull("${s.diskBus}0", s.storage, "${s.diskGiB} GiB", s.diskFormat).joinToString(" · "))
                        SummaryRow(stringResource(R.string.create_step_cpu), stringResource(R.string.create_summary_cpu, s.sockets, s.cores, s.cpuType))
                        SummaryRow(
                            stringResource(R.string.create_step_memory),
                            formatMiB(s.memoryMiB) + "  ·  " + when {
                                !s.ballooning -> stringResource(R.string.res_balloon_off)
                                s.balloonMinMiB != null -> stringResource(R.string.res_balloon_min, formatMiB(s.balloonMinMiB))
                                else -> stringResource(R.string.create_ballooning)
                            },
                        )
                        SummaryRow(stringResource(R.string.create_step_network), CreateApi.vmParams(s, diskIsFile)["net0"].orEmpty())
                    }
                    WizardSwitch(stringResource(R.string.create_start_after), null, start) { start = it }
                    MissingPrivsCard(CreateApi.missingForVm(perms, server?.authId.orEmpty(), s))
                }
                createError?.let { e ->
                    CreateErrorCard(errorMessage(context, e, server?.host.orEmpty(), server?.auth), CreateChecks.isForbidden(e))
                }
            }
        }
    }
}

@Composable
private fun osLabel(type: String): String = when (type) {
    "l26" -> stringResource(R.string.create_os_linux)
    "win11" -> stringResource(R.string.create_os_win11)
    "win10" -> stringResource(R.string.create_os_win10)
    "win8" -> stringResource(R.string.create_os_win8)
    "win7" -> stringResource(R.string.create_os_win7)
    "solaris" -> stringResource(R.string.create_os_solaris)
    else -> stringResource(R.string.create_os_other)
}

private fun scsiLabel(hw: String): String = when (hw) {
    "virtio-scsi-single" -> "VirtIO SCSI single"
    "virtio-scsi-pci" -> "VirtIO SCSI"
    "lsi" -> "LSI 53C895A"
    "lsi53c810" -> "LSI 53C810"
    "megasas" -> "MegaRAID SAS"
    "pvscsi" -> "VMware PVSCSI"
    else -> hw
}

private fun busLabel(bus: String): String = when (bus) {
    "scsi" -> "SCSI"
    "virtio" -> "VirtIO Block"
    "sata" -> "SATA"
    "ide" -> "IDE"
    else -> bus
}

private fun nicLabel(model: String): String = when (model) {
    "virtio" -> "VirtIO"
    "e1000" -> "Intel E1000"
    "e1000e" -> "Intel E1000E"
    "rtl8139" -> "Realtek RTL8139"
    "vmxnet3" -> "VMware vmxnet3"
    else -> model
}
