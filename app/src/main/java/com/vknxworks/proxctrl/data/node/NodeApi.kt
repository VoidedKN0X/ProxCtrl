package com.vknxworks.proxctrl.data.node

import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.guests.GuestApi
import com.vknxworks.proxctrl.data.guests.RrdPoint
import com.vknxworks.proxctrl.data.guests.Timeframe
import com.vknxworks.proxctrl.data.guests.long
import com.vknxworks.proxctrl.data.guests.str
import com.vknxworks.proxctrl.data.overview.NodeStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One systemd service from GET /nodes/{node}/services. */
data class NodeService(
    val service: String,
    val name: String,
    val description: String,
    /** systemd SubState: running, dead, exited, failed… */
    val state: String,
    /** systemd ActiveState: active, inactive, failed… */
    val activeState: String,
    /** systemd UnitFileState: enabled, disabled, masked, not-found… */
    val unitState: String,
) {
    val isRunning: Boolean get() = activeState == "active" || state == "running"
    val isFailed: Boolean get() = activeState == "failed" || state == "failed"

    /** Proxmox refuses to stop these ("refusing to stop essential service"); restart still works. */
    val isEssential: Boolean get() = service in NodeApi.ESSENTIAL

    /** Stopping or restarting these can cut ProxCtrl's own connection to the node. */
    val carriesConnection: Boolean get() = service in NodeApi.CONNECTION
}

enum class ServiceAction(val api: String) { START("start"), STOP("stop"), RESTART("restart") }

/** One available update from GET /nodes/{node}/apt/update. */
data class PackageUpdate(
    val name: String,
    val title: String,
    val oldVersion: String,
    val newVersion: String,
    val origin: String,
    val section: String,
    val priority: String,
    val description: String,
)

/** Whether the node runs the newest installed kernel, from GET /nodes/{node}/apt/versions. */
data class KernelState(
    /** Running kernel release, e.g. "7.0.14-16-pve". */
    val running: String,
    /** Newest installed kernel release, or null when it can't be told from the package list. */
    val newestInstalled: String?,
) {
    /** True only when a newer kernel than the running one is installed. */
    val rebootRequired: Boolean
        get() = newestInstalled != null && running.isNotBlank() && NodeApi.compareReleases(newestInstalled, running) > 0
}

/** One interface from GET /nodes/{node}/network. */
data class NetInterface(
    val iface: String,
    val type: String,
    val active: Boolean,
    val autostart: Boolean,
    val exists: Boolean,
    val method: String,
    val cidr: String?,
    val gateway: String?,
    val cidr6: String?,
    val gateway6: String?,
    /** Bridge ports, bond slaves or OVS ports. */
    val ports: String?,
    val vlanAware: Boolean,
    val vlanRawDevice: String?,
    val vlanId: String?,
    val mtu: String?,
    val comments: String?,
)

data class SdnZone(val zone: String, val type: String, val bridge: String?, val pending: Boolean)

data class SdnVnet(val vnet: String, val zone: String, val tag: String?, val alias: String?, val vlanAware: Boolean, val pending: Boolean)

/**
 * The node's own endpoints. Reading status, graphs, services, network and package versions needs
 * Sys.Audit on /nodes/{node}; service actions, listing updates, refreshing the package database and
 * changelogs need Sys.Modify; reboot and shutdown need Sys.PowerMgmt. SDN lists only what the user
 * has SDN.Audit (or SDN.Allocate) on.
 */
object NodeApi {
    const val PRIV_AUDIT = "Sys.Audit"
    const val PRIV_MODIFY = "Sys.Modify"
    const val PRIV_POWER = "Sys.PowerMgmt"
    const val PRIV_CONSOLE = "Sys.Console"

    /** pve-manager's essential services: stopping them is refused by Proxmox. */
    val ESSENTIAL = setOf("pveproxy", "pvedaemon", "pve-cluster")

    /** Services ProxCtrl's connection goes through (API, its backend, SSH for the user). */
    val CONNECTION = setOf("pveproxy", "pvedaemon", "pve-cluster", "sshd")

    fun aclPath(node: String) = "/nodes/$node"

    suspend fun status(client: PveClient, node: String): NodeStatus = NodeStatus.parse(node, client.get("/nodes/$node/status"))

    suspend fun rrd(client: PveClient, node: String, timeframe: Timeframe): List<RrdPoint> =
        GuestApi.parseRrd(client.get("/nodes/$node/rrddata", mapOf("timeframe" to timeframe.api, "cf" to "AVERAGE")))

    // --- Services ---

    suspend fun services(client: PveClient, node: String): List<NodeService> = parseServices(client.get("/nodes/$node/services"))

    suspend fun serviceAction(client: PveClient, node: String, service: String, action: ServiceAction): String? =
        (client.post("/nodes/$node/services/$service/${action.api}") as? JsonPrimitive)?.content

    fun parseServices(data: JsonElement?): List<NodeService> =
        (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val service = o.str("service") ?: o.str("name") ?: return@mapNotNull null
            NodeService(
                service = service,
                name = o.str("name") ?: service,
                description = o.str("desc").orEmpty(),
                state = o.str("state").orEmpty(),
                activeState = o.str("active-state").orEmpty(),
                unitState = o.str("unit-state").orEmpty(),
            )
        }.sortedBy { it.service }

    // --- Updates ---

    suspend fun updates(client: PveClient, node: String): List<PackageUpdate> = parseUpdates(client.get("/nodes/$node/apt/update"))

    /** apt-get update, as a task. */
    suspend fun refreshPackages(client: PveClient, node: String): String? =
        (client.post("/nodes/$node/apt/update") as? JsonPrimitive)?.content

    suspend fun changelog(client: PveClient, node: String, pkg: String, version: String?): String {
        val query = buildMap {
            put("name", pkg)
            if (!version.isNullOrBlank()) put("version", version)
        }
        return (client.get("/nodes/$node/apt/changelog", query) as? JsonPrimitive)?.content.orEmpty()
    }

    suspend fun kernelState(client: PveClient, node: String): KernelState = parseKernelState(client.get("/nodes/$node/apt/versions"))

    fun parseUpdates(data: JsonElement?): List<PackageUpdate> =
        (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val name = o.str("Package") ?: return@mapNotNull null
            PackageUpdate(
                name = name,
                title = o.str("Title").orEmpty(),
                oldVersion = o.str("OldVersion").orEmpty(),
                newVersion = o.str("Version").orEmpty(),
                origin = o.str("Origin").orEmpty(),
                section = o.str("Section").orEmpty(),
                priority = o.str("Priority").orEmpty(),
                description = o.str("Description").orEmpty(),
            )
        }.sortedBy { it.name }

    /** Kernel packages are named after their release: proxmox-kernel-7.0.14-16-pve(-signed). */
    private val KERNEL_PKG = Regex("^(?:pve|proxmox)-kernel-(\\d+\\.\\d+\\.\\d+-\\d+-pve)(?:-signed)?$")

    fun parseKernelState(data: JsonElement?): KernelState {
        val list = (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val running = list.firstOrNull { it.str("Package") == "proxmox-ve" }?.str("RunningKernel").orEmpty()
        val installed = list
            .filter { it.str("CurrentState") == "Installed" }
            .mapNotNull { KERNEL_PKG.find(it.str("Package").orEmpty())?.groupValues?.get(1) }
        val newest = installed.maxWithOrNull(::compareReleases)
        return KernelState(running, newest)
    }

    /** Compares kernel releases like "6.8.12-4-pve" number by number. */
    fun compareReleases(a: String, b: String): Int {
        val x = Regex("\\d+").findAll(a).map { it.value.toLongOrNull() ?: 0L }.toList()
        val y = Regex("\\d+").findAll(b).map { it.value.toLongOrNull() ?: 0L }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0L }).compareTo(y.getOrElse(i) { 0L })
            if (d != 0) return d
        }
        return 0
    }

    // --- Network ---

    suspend fun network(client: PveClient, node: String): List<NetInterface> = parseNetwork(client.get("/nodes/$node/network"))

    suspend fun sdnZones(client: PveClient): List<SdnZone> =
        (client.get("/cluster/sdn/zones") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            SdnZone(
                zone = o.str("zone") ?: return@mapNotNull null,
                type = o.str("type").orEmpty(),
                bridge = o.str("bridge"),
                pending = o["pending"] != null && o.str("pending") != "0",
            )
        }.sortedBy { it.zone }

    suspend fun sdnVnets(client: PveClient): List<SdnVnet> =
        (client.get("/cluster/sdn/vnets") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            SdnVnet(
                vnet = o.str("vnet") ?: return@mapNotNull null,
                zone = o.str("zone").orEmpty(),
                tag = o.str("tag"),
                alias = o.str("alias"),
                vlanAware = (o.long("vlanaware") ?: 0L) != 0L,
                pending = o["pending"] != null && o.str("pending") != "0",
            )
        }.sortedBy { it.vnet }

    fun parseNetwork(data: JsonElement?): List<NetInterface> =
        (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val iface = o.str("iface") ?: return@mapNotNull null
            NetInterface(
                iface = iface,
                type = o.str("type").orEmpty(),
                active = (o.long("active") ?: 0L) != 0L,
                autostart = (o.long("autostart") ?: 0L) != 0L,
                exists = (o.long("exists") ?: 0L) != 0L,
                method = o.str("method").orEmpty(),
                cidr = o.str("cidr") ?: o.str("address")?.let { a -> o.str("netmask")?.let { "$a/$it" } ?: a },
                gateway = o.str("gateway"),
                cidr6 = o.str("cidr6") ?: o.str("address6")?.let { a -> o.str("netmask6")?.let { "$a/$it" } ?: a },
                gateway6 = o.str("gateway6"),
                ports = o.str("bridge_ports") ?: o.str("slaves") ?: o.str("ovs_ports"),
                vlanAware = (o.long("bridge_vlan_aware") ?: 0L) != 0L,
                vlanRawDevice = o.str("vlan-raw-device"),
                vlanId = o.str("vlan-id"),
                mtu = o.str("mtu"),
                comments = o.str("comments")?.trim()?.takeIf { it.isNotBlank() },
            )
        }.sortedWith(compareBy({ typeOrder(it.type) }, { it.iface }))

    private fun typeOrder(type: String) = when (type) {
        "bridge", "OVSBridge" -> 0
        "bond", "OVSBond" -> 1
        "eth" -> 2
        "vlan", "OVSIntPort" -> 3
        else -> 4
    }

    // --- Power ---

    /** Reboots or shuts down the node (Proxmox waits two seconds, then does it). */
    suspend fun power(client: PveClient, node: String, reboot: Boolean) {
        client.post("/nodes/$node/status", mapOf("command" to if (reboot) "reboot" else "shutdown"))
    }
}
