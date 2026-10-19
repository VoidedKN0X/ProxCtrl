package com.vknxworks.proxctrl.data.overview

import com.vknxworks.proxctrl.data.guests.long
import com.vknxworks.proxctrl.data.guests.num
import com.vknxworks.proxctrl.data.guests.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** GET /nodes: one node of the cluster (or the only node). */
@Serializable
data class NodeEntry(
    val node: String = "",
    val status: String = "",
    val cpu: Double = 0.0,
    val maxcpu: Double = 0.0,
    val mem: Double = 0.0,
    val maxmem: Double = 0.0,
    val disk: Double = 0.0,
    val maxdisk: Double = 0.0,
    val uptime: Double = 0.0,
) {
    val isOnline: Boolean get() = status == "online"
}

/** GET /nodes/{node}/status, the parts the app shows. */
@Serializable
data class NodeStatus(
    val node: String,
    val cpu: Double = 0.0,
    val cpus: Int = 0,
    val sockets: Int = 0,
    val cpuModel: String = "",
    /** IO delay as a fraction (0..1). */
    val ioWait: Double = 0.0,
    val loadAvg: List<Double> = emptyList(),
    val memUsed: Long = 0,
    val memTotal: Long = 0,
    val swapUsed: Long = 0,
    val swapTotal: Long = 0,
    val rootUsed: Long = 0,
    val rootTotal: Long = 0,
    val uptime: Long = 0,
    /** "9.2.18" from "pve-manager/9.2.18/614bede5…". */
    val pveVersion: String = "",
    /** Kernel release, e.g. "7.0.14-16-pve". */
    val kernel: String = "",
    val bootMode: String = "",
    val secureBoot: Boolean = false,
) {
    val memFraction: Double? get() = if (memTotal > 0) memUsed.toDouble() / memTotal else null
    val swapFraction: Double? get() = if (swapTotal > 0) swapUsed.toDouble() / swapTotal else null
    val rootFraction: Double? get() = if (rootTotal > 0) rootUsed.toDouble() / rootTotal else null

    companion object {
        fun parse(node: String, data: JsonElement?): NodeStatus {
            val o = data as? JsonObject ?: JsonObject(emptyMap())
            val mem = o["memory"] as? JsonObject
            val swap = o["swap"] as? JsonObject
            val root = o["rootfs"] as? JsonObject
            val cpuInfo = o["cpuinfo"] as? JsonObject
            val kernel = o["current-kernel"] as? JsonObject
            val boot = o["boot-info"] as? JsonObject
            // loadavg comes as strings: ["0.52","0.61","0.58"].
            val load = (o["loadavg"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.content.toDoubleOrNull() } }
            val pve = o.str("pveversion").orEmpty()
            return NodeStatus(
                node = node,
                cpu = o.num("cpu") ?: 0.0,
                cpus = cpuInfo?.long("cpus")?.toInt() ?: 0,
                sockets = cpuInfo?.long("sockets")?.toInt() ?: 0,
                cpuModel = cpuInfo?.str("model").orEmpty(),
                ioWait = o.num("wait") ?: 0.0,
                loadAvg = load,
                memUsed = mem?.long("used") ?: 0,
                memTotal = mem?.long("total") ?: 0,
                swapUsed = swap?.long("used") ?: 0,
                swapTotal = swap?.long("total") ?: 0,
                rootUsed = root?.long("used") ?: 0,
                rootTotal = root?.long("total") ?: 0,
                uptime = o.long("uptime") ?: 0,
                pveVersion = pve.split('/').getOrNull(1) ?: pve,
                kernel = kernel?.str("release") ?: o.str("kversion")?.split(' ')?.getOrNull(1).orEmpty(),
                bootMode = boot?.str("mode").orEmpty(),
                secureBoot = (boot?.long("secureboot") ?: 0) != 0L || boot?.str("secureboot") == "true",
            )
        }
    }
}

/** GET /cluster/resources?type=storage */
@Serializable
data class StorageEntry(
    val id: String = "",
    val storage: String = "",
    val node: String = "",
    val status: String = "",
    val disk: Double = 0.0,
    val maxdisk: Double = 0.0,
    val content: String = "",
    val plugintype: String = "",
    val shared: Int = 0,
) {
    val fraction: Double? get() = if (maxdisk > 0) disk / maxdisk else null
    val isAvailable: Boolean get() = status == "available"
    val holdsBackups: Boolean get() = content.split(',').any { it.trim() == "backup" }
}

/** A Proxmox task from GET /nodes/{node}/tasks or /cluster/tasks. */
@Serializable
data class TaskEntry(
    val upid: String = "",
    val node: String = "",
    val type: String = "",
    val id: String = "",
    val user: String = "",
    /** "OK", "WARNINGS: n", an error message, or absent while running. */
    val status: String = "",
    val starttime: Long = 0,
    val endtime: Long = 0,
) {
    val isRunning: Boolean get() = endtime == 0L && (status.isBlank() || status == "RUNNING")
    val isOk: Boolean get() = status == "OK"
    val hasWarnings: Boolean get() = status.startsWith("WARNINGS")
    val isError: Boolean get() = !isRunning && !isOk && !hasWarnings && status.isNotBlank()
}

/** One backup file from GET /nodes/{node}/storage/{storage}/content?content=backup. */
data class BackupFile(val vmid: Int, val ctime: Long)

/** One entry of GET /cluster/ha/status/current. */
data class HaEntry(val id: String, val type: String, val status: String, val state: String, val sid: String, val node: String)

object OverviewParse {
    fun backups(data: JsonElement?): List<BackupFile> = (data as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val vmid = o.long("vmid")?.toInt() ?: return@mapNotNull null
        BackupFile(vmid, o.long("ctime") ?: 0)
    }

    fun ha(data: JsonElement?): List<HaEntry> = (data as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        HaEntry(
            id = o.str("id").orEmpty(),
            type = o.str("type").orEmpty(),
            status = o.str("status").orEmpty(),
            state = o.str("state").orEmpty(),
            sid = o.str("sid").orEmpty(),
            node = o.str("node").orEmpty(),
        )
    }

    /** VMIDs from GET /cluster/backup-info/not-backed-up. */
    fun notBackedUp(data: JsonElement?): Set<Int> =
        (data as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.long("vmid")?.toInt() }.toSet()

    fun subscriptionStatus(data: JsonElement?): String = (data as? JsonObject)?.str("status").orEmpty()

    fun updateCount(data: JsonElement?): Int = (data as? JsonArray)?.size ?: 0
}
