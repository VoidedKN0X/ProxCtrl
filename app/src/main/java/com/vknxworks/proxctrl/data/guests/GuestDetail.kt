package com.vknxworks.proxctrl.data.guests

import com.vknxworks.proxctrl.data.api.PveClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Which guest a page is about. */
data class GuestRef(val node: String, val type: GuestType, val vmid: Int) {
    val base: String get() = "/nodes/$node/${type.api}/$vmid"
    val aclPath: String get() = "/vms/$vmid"
}

// Small lenient readers for Proxmox JSON, where numbers sometimes arrive as strings.
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it != "null" }
internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() }

/** GET {base}/status/current */
data class GuestStatus(
    val status: String,
    /** QEMU's own state (running, paused, suspended, prelaunch…); null for containers. */
    val qmpStatus: String?,
    val uptime: Long,
    val cpu: Double,
    val cpus: Double,
    val mem: Long,
    val maxMem: Long,
    val disk: Long,
    val maxDisk: Long,
    val haManaged: Boolean,
    val haState: String?,
    val lock: String?,
    val name: String?,
) {
    /** The most precise run state: QEMU's state where known. */
    val runState: String get() = qmpStatus?.takeIf { status == "running" } ?: status

    companion object {
        fun parse(data: JsonElement?): GuestStatus {
            val o = data as? JsonObject ?: JsonObject(emptyMap())
            val ha = o["ha"] as? JsonObject
            return GuestStatus(
                status = o.str("status") ?: "unknown",
                qmpStatus = o.str("qmpstatus"),
                uptime = o.long("uptime") ?: 0,
                cpu = o.num("cpu") ?: 0.0,
                cpus = o.num("cpus") ?: 0.0,
                mem = o.long("mem") ?: 0,
                maxMem = o.long("maxmem") ?: 0,
                disk = o.long("disk") ?: 0,
                maxDisk = o.long("maxdisk") ?: 0,
                haManaged = (ha?.long("managed") ?: 0) != 0L,
                haState = ha?.str("state"),
                lock = o.str("lock"),
                name = o.str("name"),
            )
        }
    }
}

/** The bits of GET {base}/config the guest page shows. */
data class GuestConfig(
    val description: String,
    val onBoot: Boolean,
    /** Start order and delays from "order=1,up=30,down=60". */
    val startOrder: Int?,
    val startUpDelay: Int?,
    val startDownDelay: Int?,
    val osType: String?,
    val agentEnabled: Boolean,
    val hostname: String?,
    /** Config digest, sent back when editing so a concurrent change isn't overwritten. */
    val digest: String?,
) {
    companion object {
        fun parse(data: JsonElement?): GuestConfig {
            val o = data as? JsonObject ?: JsonObject(emptyMap())
            val startup = o.str("startup").orEmpty().split(',').mapNotNull {
                val kv = it.split('=', limit = 2)
                if (kv.size == 2) kv[0].trim() to kv[1].trim() else if (kv[0].isNotBlank()) "order" to kv[0].trim() else null
            }.toMap()
            // "1", "enabled=1,fstrim_cloned_disks=1" or "0"
            val agent = o.str("agent").orEmpty()
            val agentOn = agent.split(',').any { it.trim() == "1" || it.trim() == "enabled=1" }
            return GuestConfig(
                description = o.str("description").orEmpty(),
                onBoot = (o.long("onboot") ?: 0) != 0L,
                startOrder = startup["order"]?.toIntOrNull(),
                startUpDelay = startup["up"]?.toIntOrNull(),
                startDownDelay = startup["down"]?.toIntOrNull(),
                osType = o.str("ostype"),
                agentEnabled = agentOn,
                hostname = o.str("hostname"),
                digest = o.str("digest"),
            )
        }
    }
}

/** One IP address of a guest. */
data class GuestAddress(val iface: String, val address: String, val prefix: Int?, val ipv6: Boolean, val mac: String?)

/** One sample of GET {base}/rrddata. Missing values (gaps) are null. */
data class RrdPoint(val time: Long, val values: Map<String, Double?>)

enum class Timeframe(val api: String) { HOUR("hour"), DAY("day"), WEEK("week"), MONTH("month"), YEAR("year") }

object GuestApi {

    suspend fun status(client: PveClient, ref: GuestRef): GuestStatus = GuestStatus.parse(client.get("${ref.base}/status/current"))

    suspend fun config(client: PveClient, ref: GuestRef): GuestConfig = GuestConfig.parse(client.get("${ref.base}/config"))

    /**
     * IP addresses: for containers from GET /lxc/{vmid}/interfaces ("inet": "10.0.0.5/24"), for VMs from
     * the guest agent (network-get-interfaces). Loopback and link-local addresses are left out.
     */
    suspend fun addresses(client: PveClient, ref: GuestRef): List<GuestAddress> = when (ref.type) {
        GuestType.LXC -> parseLxcInterfaces(client.get("${ref.base}/interfaces"))
        GuestType.QEMU -> parseAgentInterfaces(client.get("${ref.base}/agent/network-get-interfaces"))
    }

    /** Operating system name from the guest agent (VMs), e.g. "Debian GNU/Linux 13 (trixie)". */
    suspend fun osName(client: PveClient, ref: GuestRef): String? {
        val result = (client.get("${ref.base}/agent/get-osinfo") as? JsonObject)?.get("result") as? JsonObject ?: return null
        return result.str("pretty-name") ?: listOfNotNull(result.str("name"), result.str("version")).joinToString(" ").ifBlank { null }
    }

    suspend fun rrd(client: PveClient, ref: GuestRef, timeframe: Timeframe): List<RrdPoint> =
        parseRrd(client.get("${ref.base}/rrddata", mapOf("timeframe" to timeframe.api, "cf" to "AVERAGE")))

    /** Saves the notes (Markdown). Empty notes delete the description. Needs VM.Config.Options. */
    suspend fun setNotes(client: PveClient, ref: GuestRef, notes: String, digest: String?) {
        val params = buildMap {
            if (notes.isBlank()) put("delete", "description") else put("description", notes)
            if (!digest.isNullOrBlank()) put("digest", digest)
        }
        client.put("${ref.base}/config", params)
    }

    fun parseLxcInterfaces(data: JsonElement?): List<GuestAddress> {
        val list = data as? JsonArray ?: return emptyList()
        return list.mapNotNull { it as? JsonObject }.flatMap { o ->
            val name = o.str("name").orEmpty()
            val mac = o.str("hwaddr")
            listOfNotNull(o.str("inet")?.let { it to false }, o.str("inet6")?.let { it to true })
                .flatMap { (value, v6) -> value.split(' ', ',').filter { it.isNotBlank() }.map { it to v6 } }
                .map { (cidr, v6) -> GuestAddress(name, cidr.substringBefore('/'), cidr.substringAfter('/', "").toIntOrNull(), v6, mac) }
        }.filter(::isUseful)
    }

    fun parseAgentInterfaces(data: JsonElement?): List<GuestAddress> {
        val list = (data as? JsonObject)?.get("result") as? JsonArray ?: return emptyList()
        return list.mapNotNull { it as? JsonObject }.flatMap { o ->
            val name = o.str("name").orEmpty()
            val mac = o.str("hardware-address")
            (o["ip-addresses"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { a ->
                val ip = a.str("ip-address") ?: return@mapNotNull null
                GuestAddress(name, ip, a.long("prefix")?.toInt(), a.str("ip-address-type") == "ipv6", mac)
            }
        }.filter(::isUseful)
    }

    private fun isUseful(a: GuestAddress): Boolean {
        val ip = a.address.lowercase()
        return a.iface != "lo" && !ip.startsWith("127.") && ip != "::1" && !ip.startsWith("fe80:") && !ip.startsWith("169.254.")
    }

    fun parseRrd(data: JsonElement?): List<RrdPoint> {
        val list = data as? JsonArray ?: return emptyList()
        return list.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val time = o.long("time") ?: return@mapNotNull null
            RrdPoint(time, o.keys.filter { it != "time" }.associateWith { o.num(it) })
        }.sortedBy { it.time }
    }
}
