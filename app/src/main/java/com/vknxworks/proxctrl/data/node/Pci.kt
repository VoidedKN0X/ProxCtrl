package com.vknxworks.proxctrl.data.node

import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.guests.long
import com.vknxworks.proxctrl.data.guests.str
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One PCI device of the node: GET /nodes/{node}/hardware/pci. */
data class PciDevice(
    /** Full address, "0000:01:00.0". */
    val id: String,
    /** PCI class, "0x030000". */
    val classCode: String,
    val vendorName: String?,
    val deviceName: String?,
    val vendorId: String,
    val deviceId: String,
    /** -1 when the device is in no IOMMU group (passthrough isn't possible then). */
    val iommuGroup: Int,
) {
    /** Display controllers (class 03): graphics cards. */
    val isGpu: Boolean get() = classCode.removePrefix("0x").startsWith("03")

    val label: String
        get() = listOfNotNull(vendorName?.takeIf { it.isNotBlank() }, deviceName?.takeIf { it.isNotBlank() }).joinToString(" ")
            .ifBlank { "${vendorId.removePrefix("0x")}:${deviceId.removePrefix("0x")}" }
}

/** A VM's hostpciN line: the addresses it names directly, or the resource mapping it uses. */
data class HostPci(val addresses: List<String>, val mapping: String?)

/** A VM that has a PCI device passed through. */
data class PciUse(val vmid: Int, val name: String, val running: Boolean, val key: String, val raw: String, val mapping: String?)

/** A container that is given device files of the host. */
data class ContainerDevices(val vmid: Int, val name: String, val running: Boolean, val paths: List<String>)

data class PciOverview(
    val devices: List<PciDevice>,
    /** Device address to the VMs it is passed through to. */
    val uses: Map<String, List<PciUse>>,
    /** Passthrough lines whose device isn't in the node's list (gone, or a mapping this sign-in can't see). */
    val unmatched: List<PciUse>,
    val containers: List<ContainerDevices>,
    /** Guests whose configuration couldn't be read (no VM.Audit on them). */
    val unreadable: Int,
)

/**
 * Which PCI device is given to which guest. Proxmox has no single call for this, so it is put together
 * from the node's device list (GET /nodes/{node}/hardware/pci, Sys.Audit or Sys.Modify on /), the PCI
 * resource mappings (GET /cluster/mapping/pci) and each guest's configuration (hostpciN for VMs; devN
 * and raw lxc.mount.entry lines for containers). Read-only.
 *
 * A VM gets a whole device (vfio). Containers don't get a PCI device: they get device files such as
 * /dev/nvidia0 and share the card through the host's driver, and Proxmox doesn't record which card a
 * device file belongs to.
 */
object PciApi {
    private val ADDRESS = Regex("^(?:([a-f0-9]{4,}):)?([a-f0-9]{2}):([a-f0-9]{2})(?:\\.([a-f0-9]))?$", RegexOption.IGNORE_CASE)
    private val HOSTPCI = Regex("^hostpci\\d+$")
    private val LXC_DEV = Regex("^dev\\d+$")

    suspend fun devices(client: PveClient, node: String): List<PciDevice> = parseDevices(client.get("/nodes/$node/hardware/pci"))

    fun parseDevices(data: JsonElement?): List<PciDevice> =
        (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            PciDevice(
                id = o.str("id") ?: return@mapNotNull null,
                classCode = o.str("class").orEmpty(),
                vendorName = o.str("vendor_name"),
                deviceName = o.str("device_name"),
                vendorId = o.str("vendor").orEmpty(),
                deviceId = o.str("device").orEmpty(),
                iommuGroup = o.long("iommugroup")?.toInt() ?: -1,
            )
        }.sortedBy { it.id }

    /** Mapping name to the device addresses it stands for on [node]. */
    fun parseMappings(data: JsonElement?, node: String): Map<String, List<String>> =
        (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            val paths = (o["map"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                .map { entry -> entry.split(',').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap() }
                .filter { it["node"] == node }
                .flatMap { it["path"].orEmpty().split(';').map(String::trim).filter(String::isNotEmpty) }
            id to paths
        }.toMap()

    /** "0000:01:00,pcie=1,x-vga=1", "host=01:00.0;01:00.1" or "mapping=gpu,pcie=1". */
    fun parseHostPci(value: String): HostPci {
        var mapping: String? = null
        val addresses = mutableListOf<String>()
        value.split(',').forEachIndexed { i, part ->
            val kv = part.split('=', limit = 2)
            when {
                kv.size == 2 && kv[0].trim() == "mapping" -> mapping = kv[1].trim()
                kv.size == 2 && kv[0].trim() == "host" -> addresses += kv[1].split(';').map(String::trim).filter(String::isNotEmpty)
                kv.size == 1 && i == 0 -> addresses += part.split(';').map(String::trim).filter(String::isNotEmpty)
            }
        }
        return HostPci(addresses, mapping)
    }

    /**
     * True when [address] from a configuration names [deviceId] ("0000:01:00.0"). The domain may be
     * left out (0000 is meant), and without a function ("01:00") every function of the slot is meant.
     */
    fun matches(deviceId: String, address: String): Boolean {
        val d = ADDRESS.find(deviceId.trim()) ?: return false
        val a = ADDRESS.find(address.trim()) ?: return false
        val (dDomain, dBus, dSlot, dFn) = d.destructured
        val (aDomain, aBus, aSlot, aFn) = a.destructured
        return dDomain.ifEmpty { "0000" }.equals(aDomain.ifEmpty { "0000" }, ignoreCase = true) &&
            dBus.equals(aBus, ignoreCase = true) && dSlot.equals(aSlot, ignoreCase = true) &&
            (aFn.isEmpty() || dFn.isEmpty() || aFn.equals(dFn, ignoreCase = true))
    }

    /** The host device files a container's configuration hands to it. */
    fun containerDevicePaths(config: JsonObject): List<String> {
        val fromDev = config.keys.filter { LXC_DEV.matches(it) }.sorted().mapNotNull { key ->
            val first = config.str(key)?.substringBefore(',')?.trim().orEmpty()
            first.removePrefix("path=").takeIf { it.startsWith("/") }
        }
        // Raw LXC lines come as [key, value] pairs: ["lxc.mount.entry", "/dev/nvidia0 dev/nvidia0 none bind,optional,create=file"].
        val fromMounts = (config["lxc"] as? JsonArray).orEmpty().mapNotNull { it as? JsonArray }.mapNotNull { pair ->
            val key = (pair.getOrNull(0) as? JsonPrimitive)?.content
            val value = (pair.getOrNull(1) as? JsonPrimitive)?.content.orEmpty()
            value.trim().substringBefore(' ').takeIf { key == "lxc.mount.entry" && it.startsWith("/dev/") }
        }
        return (fromDev + fromMounts).distinct()
    }

    /** Puts devices, mappings and guest configurations together. */
    fun combine(
        devices: List<PciDevice>,
        mappings: Map<String, List<String>>,
        configs: List<Pair<Guest, JsonObject>>,
        unreadable: Int,
    ): PciOverview {
        val uses = linkedMapOf<String, MutableList<PciUse>>()
        val unmatched = mutableListOf<PciUse>()
        val containers = mutableListOf<ContainerDevices>()
        for ((guest, config) in configs.sortedBy { it.first.vmid }) {
            if (guest.type == GuestType.LXC) {
                val paths = containerDevicePaths(config)
                if (paths.isNotEmpty()) containers += ContainerDevices(guest.vmid, guest.name, guest.isRunning, paths)
                continue
            }
            for (key in config.keys.filter { HOSTPCI.matches(it) }.sorted()) {
                val raw = config.str(key).orEmpty()
                val parsed = parseHostPci(raw)
                val use = PciUse(guest.vmid, guest.name, guest.isRunning, key, raw, parsed.mapping)
                val addresses = parsed.addresses + parsed.mapping?.let { mappings[it].orEmpty() }.orEmpty()
                val hit = devices.filter { dev -> addresses.any { matches(dev.id, it) } }
                if (hit.isEmpty()) unmatched += use else hit.forEach { dev -> uses.getOrPut(dev.id) { mutableListOf() } += use }
            }
        }
        return PciOverview(devices, uses, unmatched, containers, unreadable)
    }

    suspend fun overview(client: PveClient, node: String, guests: List<Guest>): PciOverview = coroutineScope {
        val devices = devices(client, node)
        // Mappings are optional: without the privilege to see them, mapped devices show as unmatched.
        val mappings = runCatching { parseMappings(client.get("/cluster/mapping/pci"), node) }.getOrDefault(emptyMap())
        val limit = Semaphore(6)
        val read = guests.filter { it.node == node && !it.isTemplate }.map { g ->
            async {
                limit.withPermit {
                    g to runCatching { client.get("/nodes/$node/${g.type.api}/${g.vmid}/config") as? JsonObject }.getOrNull()
                }
            }
        }.awaitAll()
        val configs = read.mapNotNull { (g, c) -> c?.let { g to it } }
        combine(devices, mappings, configs, unreadable = read.size - configs.size)
    }
}
