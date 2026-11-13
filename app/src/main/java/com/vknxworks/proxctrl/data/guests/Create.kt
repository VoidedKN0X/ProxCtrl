package com.vknxworks.proxctrl.data.guests

import com.vknxworks.proxctrl.data.api.Permissions
import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.node.NodeApi
import com.vknxworks.proxctrl.data.storage.ContentItem
import com.vknxworks.proxctrl.data.storage.StorageApi
import com.vknxworks.proxctrl.data.storage.StorageInfo
import kotlinx.serialization.json.JsonPrimitive

/** How a container gets its IPv4 address. */
enum class Ipv4Mode { DHCP, STATIC, NONE }

/** How a container gets its IPv6 address (AUTO is SLAAC). */
enum class Ipv6Mode { NONE, DHCP, AUTO, STATIC }

/** Network device settings shared by both wizards. */
data class NicSpec(
    val bridge: String = "vmbr0",
    /** VLAN tag 1..4094, or null for none. */
    val vlan: Int? = null,
    val firewall: Boolean = true,
)

/** Everything the container wizard collects, for POST /nodes/{node}/lxc. */
data class LxcSpec(
    val node: String,
    val vmid: Int,
    val hostname: String,
    /** Volume ID of the vztmpl, e.g. "local:vztmpl/debian-13-standard_13.1-2_amd64.tar.zst". */
    val template: String,
    val password: String,
    val sshKeys: String,
    val unprivileged: Boolean,
    val nesting: Boolean,
    val storage: String,
    val diskGiB: Int,
    val cores: Int,
    val memoryMiB: Long,
    val swapMiB: Long,
    val nic: NicSpec,
    val ipv4: Ipv4Mode,
    val ipv4Cidr: String,
    val ipv4Gateway: String,
    val ipv6: Ipv6Mode,
    val ipv6Cidr: String,
    val ipv6Gateway: String,
    val dnsDomain: String,
    val dnsServers: String,
    val start: Boolean,
)

/** Everything the VM wizard collects, for POST /nodes/{node}/qemu. */
data class VmSpec(
    val node: String,
    val vmid: Int,
    val name: String,
    /** Proxmox ostype: l26, win11, win10, win8, win7, other… */
    val osType: String,
    /** ISO volume ID, or null for no CD/DVD. */
    val iso: String?,
    val ovmf: Boolean,
    /** Storage of the EFI vars disk (and TPM state), used when [ovmf]. */
    val efiStorage: String?,
    val preEnrolledKeys: Boolean,
    val tpm: Boolean,
    val q35: Boolean,
    val scsiHw: String,
    /** scsi, virtio, sata or ide. */
    val diskBus: String,
    val storage: String,
    val diskGiB: Int,
    /** raw, qcow2 or vmdk on file storages; null = the storage's default. */
    val diskFormat: String?,
    val sockets: Int,
    val cores: Int,
    val cpuType: String,
    val memoryMiB: Long,
    val ballooning: Boolean,
    /** Ballooning minimum; null = the same as [memoryMiB]. */
    val balloonMinMiB: Long?,
    val nicModel: String,
    val nic: NicSpec,
    val agent: Boolean,
    val start: Boolean,
)

/** A privilege the sign-in seems to lack for an action, on [path]. [privs] are alternatives. */
data class MissingPriv(val path: String, val privs: List<String>)

/**
 * Creating guests and moving their disks.
 *
 * Containers (POST /nodes/{node}/lxc): VM.Allocate on /vms/{vmid}, Datastore.AllocateSpace on the
 * disk's storage, Datastore.AllocateSpace or Datastore.Audit on the template's storage, VM.Config.Disk,
 * VM.Config.CPU, VM.Config.Memory and VM.Config.Network for the settings, VM.Allocate for nesting,
 * SDN.Use on the bridge, Sys.Modify on / for privileged containers and VM.PowerMgmt to start it.
 *
 * VMs (POST /nodes/{node}/qemu): VM.Allocate, VM.Config.CPU, VM.Config.Memory, VM.Config.HWType
 * (machine, SCSI controller), VM.Config.Options (name, OS type, BIOS, agent), VM.Config.Disk (boot
 * order), VM.Config.Network, Datastore.AllocateSpace on every storage a disk is made on, access to
 * the ISO's storage, SDN.Use on the bridge and VM.PowerMgmt to start it.
 *
 * Moving a disk (move_disk / move_volume): VM.Config.Disk on the guest and Datastore.AllocateSpace
 * on the target storage. Containers must be stopped; VMs can move disks while running.
 */
object CreateApi {
    const val PRIV_ALLOCATE = "VM.Allocate"
    const val PRIV_DISK = "VM.Config.Disk"
    const val PRIV_CPU = "VM.Config.CPU"
    const val PRIV_MEMORY = "VM.Config.Memory"
    const val PRIV_NETWORK = "VM.Config.Network"
    const val PRIV_HWTYPE = "VM.Config.HWType"
    const val PRIV_OPTIONS = "VM.Config.Options"
    const val PRIV_POWER = "VM.PowerMgmt"
    const val PRIV_SPACE = "Datastore.AllocateSpace"
    const val PRIV_DS_AUDIT = "Datastore.Audit"
    const val PRIV_SDN_USE = "SDN.Use"
    const val PRIV_SYS_MODIFY = "Sys.Modify"

    /** Storage types that keep disks as files, so the image format can be chosen. */
    private val FILE_STORAGES = setOf("dir", "nfs", "cifs", "glusterfs")

    fun isFileStorage(s: StorageInfo): Boolean = s.type in FILE_STORAGES

    /** DNS names, as Proxmox wants for hostnames and VM names. */
    val DNS_NAME = Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$")
    val IPV4_CIDR = Regex("^(\\d{1,3})(\\.\\d{1,3}){3}/(\\d|[12]\\d|3[0-2])$")
    val IPV4 = Regex("^(\\d{1,3})(\\.\\d{1,3}){3}$")
    val IPV6_CIDR = Regex("^[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*/(\\d|\\d\\d|1[01]\\d|12[0-8])$")
    val IPV6 = Regex("^[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*$")

    /** Enabled, active storages on [node] that can hold [content] (images, rootdir, iso, vztmpl). */
    suspend fun storagesFor(client: PveClient, node: String, content: String): List<StorageInfo> =
        StorageApi.list(client, node).filter { it.enabled && it.active && it.holds(content) }

    /** Volumes of one [content] type on [storage], sorted by name. */
    suspend fun volumes(client: PveClient, node: String, storage: String, content: String): List<ContentItem> =
        StorageApi.parseContent(client.get("/nodes/$node/storage/$storage/content", mapOf("content" to content)))
            .filter { it.content == content }
            .sortedBy { it.name.lowercase() }

    /** Linux bridges and OVS bridges of [node] (needs Sys.Audit on the node). */
    suspend fun bridges(client: PveClient, node: String): List<String> =
        NodeApi.network(client, node).filter { it.type == "bridge" || it.type == "OVSBridge" }.map { it.iface }

    // --- Containers ---

    private fun nicTail(nic: NicSpec): List<String> = buildList {
        add("bridge=${nic.bridge.trim()}")
        if (nic.firewall) add("firewall=1")
        nic.vlan?.let { add("tag=$it") }
    }

    fun lxcParams(s: LxcSpec): Map<String, String> = buildMap {
        put("vmid", s.vmid.toString())
        put("hostname", s.hostname.trim())
        put("ostemplate", s.template)
        if (s.password.isNotEmpty()) put("password", s.password)
        val keys = s.sshKeys.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (keys.isNotEmpty()) put("ssh-public-keys", keys.joinToString("\n"))
        put("unprivileged", if (s.unprivileged) "1" else "0")
        if (s.nesting) put("features", "nesting=1")
        put("rootfs", "${s.storage}:${s.diskGiB}")
        put("cores", s.cores.toString())
        put("memory", s.memoryMiB.toString())
        put("swap", s.swapMiB.toString())
        val net = buildList {
            add("name=eth0")
            addAll(nicTail(s.nic))
            when (s.ipv4) {
                Ipv4Mode.DHCP -> add("ip=dhcp")
                Ipv4Mode.STATIC -> {
                    add("ip=${s.ipv4Cidr.trim()}")
                    if (s.ipv4Gateway.isNotBlank()) add("gw=${s.ipv4Gateway.trim()}")
                }
                Ipv4Mode.NONE -> Unit
            }
            when (s.ipv6) {
                Ipv6Mode.DHCP -> add("ip6=dhcp")
                Ipv6Mode.AUTO -> add("ip6=auto")
                Ipv6Mode.STATIC -> {
                    add("ip6=${s.ipv6Cidr.trim()}")
                    if (s.ipv6Gateway.isNotBlank()) add("gw6=${s.ipv6Gateway.trim()}")
                }
                Ipv6Mode.NONE -> Unit
            }
        }
        put("net0", net.joinToString(","))
        if (s.dnsDomain.isNotBlank()) put("searchdomain", s.dnsDomain.trim())
        if (s.dnsServers.isNotBlank()) put("nameserver", s.dnsServers.trim().split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }.joinToString(" "))
        if (s.start) put("start", "1")
    }

    /** Creates the container. Returns the task UPID. */
    suspend fun createLxc(client: PveClient, s: LxcSpec): String? =
        (client.post("/nodes/${s.node}/lxc", lxcParams(s)) as? JsonPrimitive)?.content

    // --- VMs ---

    fun vmParams(s: VmSpec, diskStorageIsFile: Boolean): Map<String, String> = buildMap {
        put("vmid", s.vmid.toString())
        if (s.name.isNotBlank()) put("name", s.name.trim())
        put("ostype", s.osType)
        if (s.q35) put("machine", "q35")
        put("bios", if (s.ovmf) "ovmf" else "seabios")
        if (s.ovmf && !s.efiStorage.isNullOrBlank()) {
            put("efidisk0", "${s.efiStorage}:1,efitype=4m" + if (s.preEnrolledKeys) ",pre-enrolled-keys=1" else "")
            if (s.tpm) put("tpmstate0", "${s.efiStorage}:1,version=v2.0")
        }
        put("scsihw", s.scsiHw)
        val diskKey = "${s.diskBus}0"
        val disk = buildList {
            add("${s.storage}:${s.diskGiB}")
            if (diskStorageIsFile && !s.diskFormat.isNullOrBlank()) add("format=${s.diskFormat}")
            // One IO thread per disk, as the web UI sets with VirtIO SCSI single and VirtIO block.
            if (s.diskBus == "virtio" || (s.diskBus == "scsi" && s.scsiHw == "virtio-scsi-single")) add("iothread=1")
        }
        put(diskKey, disk.joinToString(","))
        if (!s.iso.isNullOrBlank()) put("ide2", "${s.iso},media=cdrom")
        put("boot", "order=" + listOfNotNull(diskKey, if (!s.iso.isNullOrBlank()) "ide2" else null, "net0").joinToString(";"))
        put("sockets", s.sockets.toString())
        put("cores", s.cores.toString())
        if (s.cpuType.isNotBlank()) put("cpu", s.cpuType.trim())
        put("memory", s.memoryMiB.toString())
        when {
            !s.ballooning -> put("balloon", "0")
            s.balloonMinMiB != null && s.balloonMinMiB != s.memoryMiB -> put("balloon", s.balloonMinMiB.toString())
        }
        put("net0", (listOf(s.nicModel) + nicTail(s.nic)).joinToString(","))
        if (s.agent) put("agent", "1")
        if (s.start) put("start", "1")
    }

    /** Creates the VM. Returns the task UPID. */
    suspend fun createVm(client: PveClient, s: VmSpec, diskStorageIsFile: Boolean): String? =
        (client.post("/nodes/${s.node}/qemu", vmParams(s, diskStorageIsFile)) as? JsonPrimitive)?.content

    // --- Permissions the wizards check before creating ---

    private fun MutableList<MissingPriv>.need(p: Permissions, path: String, vararg privs: String) {
        if (privs.none { p.has(path, it) }) add(MissingPriv(path, privs.toList()))
    }

    private fun MutableList<MissingPriv>.needBridge(p: Permissions, nic: NicSpec) {
        val bridge = nic.bridge.trim()
        if (bridge.isEmpty()) return
        val path = "/sdn/zones/localnetwork/$bridge" + (nic.vlan?.let { "/$it" } ?: "")
        need(p, path, PRIV_SDN_USE)
    }

    /** What [p] seems to lack to create [s]. Empty for root@pam, which Proxmox never checks. */
    fun missingForLxc(p: Permissions, authId: String, s: LxcSpec): List<MissingPriv> {
        if (authId == "root@pam") return emptyList()
        return buildList<MissingPriv> {
            val vm = "/vms/${s.vmid}"
            need(p, vm, PRIV_ALLOCATE)
            need(p, vm, PRIV_DISK)
            need(p, vm, PRIV_CPU)
            need(p, vm, PRIV_MEMORY)
            need(p, vm, PRIV_NETWORK)
            need(p, "/storage/${s.storage}", PRIV_SPACE)
            s.template.substringBefore(':', "").takeIf { it.isNotBlank() }?.let { need(p, "/storage/$it", PRIV_SPACE, PRIV_DS_AUDIT) }
            needBridge(p, s.nic)
            if (!s.unprivileged) need(p, "/", PRIV_SYS_MODIFY)
            if (s.start) need(p, vm, PRIV_POWER)
        }.distinct()
    }

    /** What [p] seems to lack to create [s]. */
    fun missingForVm(p: Permissions, authId: String, s: VmSpec): List<MissingPriv> {
        if (authId == "root@pam") return emptyList()
        return buildList<MissingPriv> {
            val vm = "/vms/${s.vmid}"
            need(p, vm, PRIV_ALLOCATE)
            need(p, vm, PRIV_CPU)
            need(p, vm, PRIV_MEMORY)
            need(p, vm, PRIV_HWTYPE)
            need(p, vm, PRIV_OPTIONS)
            need(p, vm, PRIV_DISK)
            need(p, vm, PRIV_NETWORK)
            need(p, "/storage/${s.storage}", PRIV_SPACE)
            if (s.ovmf && !s.efiStorage.isNullOrBlank()) need(p, "/storage/${s.efiStorage}", PRIV_SPACE)
            s.iso?.substringBefore(':', "")?.takeIf { it.isNotBlank() }?.let { need(p, "/storage/$it", PRIV_SPACE, PRIV_DS_AUDIT) }
            needBridge(p, s.nic)
            if (s.start) need(p, vm, PRIV_POWER)
        }.distinct()
    }

    // --- Moving disks ---

    /**
     * Moves a VM disk to [storage] (POST {base}/move_disk). [format] only applies on file storages;
     * [deleteSource] removes the old disk afterwards instead of keeping it as an unused disk.
     * [bwLimitMiB] limits the copy speed (MiB/s; null = the storage or datacenter default).
     */
    suspend fun moveVmDisk(
        client: PveClient,
        ref: GuestRef,
        disk: String,
        storage: String,
        format: String?,
        deleteSource: Boolean,
        bwLimitMiB: Int?,
        digest: String?,
    ): String? {
        val params = buildMap {
            put("disk", disk)
            put("storage", storage)
            if (!format.isNullOrBlank()) put("format", format)
            put("delete", if (deleteSource) "1" else "0")
            bwLimitMiB?.let { put("bwlimit", (it * 1024).toString()) }
            if (!digest.isNullOrBlank()) put("digest", digest)
        }
        return (client.post("${ref.base}/move_disk", params) as? JsonPrimitive)?.content
    }

    /** Moves a container's root disk or mount point to [storage] (POST {base}/move_volume). */
    suspend fun moveLxcVolume(
        client: PveClient,
        ref: GuestRef,
        volume: String,
        storage: String,
        deleteSource: Boolean,
        bwLimitMiB: Int?,
        digest: String?,
    ): String? {
        val params = buildMap {
            put("volume", volume)
            put("storage", storage)
            put("delete", if (deleteSource) "1" else "0")
            bwLimitMiB?.let { put("bwlimit", (it * 1024).toString()) }
            if (!digest.isNullOrBlank()) put("digest", digest)
        }
        return (client.post("${ref.base}/move_volume", params) as? JsonPrimitive)?.content
    }
}
