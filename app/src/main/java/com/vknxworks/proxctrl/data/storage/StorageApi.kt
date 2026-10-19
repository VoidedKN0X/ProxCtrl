package com.vknxworks.proxctrl.data.storage

import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.guests.long
import com.vknxworks.proxctrl.data.guests.num
import com.vknxworks.proxctrl.data.guests.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One storage on one node: GET /nodes/{node}/storage (only storages the user may see are listed). */
data class StorageInfo(
    val node: String,
    val id: String,
    val type: String,
    val content: List<String>,
    val enabled: Boolean,
    val active: Boolean,
    val shared: Boolean,
    val total: Long,
    val used: Long,
    val avail: Long,
) {
    val fraction: Double? get() = if (total > 0) used.toDouble() / total else null
    fun holds(type: String): Boolean = type in content
}

/** One volume on a storage: GET /nodes/{node}/storage/{storage}/content. */
data class ContentItem(
    val volid: String,
    /** backup, iso, vztmpl, images, rootdir, snippets, import. */
    val content: String,
    /** Seconds since the epoch, 0 when unknown (ISOs and templates on some storages). */
    val ctime: Long,
    val size: Long,
    val format: String,
    val vmid: Int?,
    val notes: String,
    val isProtected: Boolean,
    /** "ok" or "failed" for verified Proxmox Backup Server snapshots, else null. */
    val verification: String?,
) {
    /** The name without the storage prefix and directories. */
    val name: String get() = volid.substringAfter(':').substringAfterLast('/')
}

/** A container template from the Proxmox appliance list: GET /nodes/{node}/aplinfo. */
data class Appliance(
    val template: String,
    val section: String,
    val os: String,
    val version: String,
    val headline: String,
    val description: String,
)

/**
 * Storages and their content. Listing needs Datastore.Audit or Datastore.AllocateSpace on the storage;
 * deleting a backup needs Datastore.Allocate, or Datastore.AllocateSpace plus VM.Backup on its guest;
 * uploading ISOs and downloading container templates need Datastore.AllocateTemplate.
 */
object StorageApi {
    const val PRIV_ALLOCATE = "Datastore.Allocate"
    const val PRIV_ALLOCATE_SPACE = "Datastore.AllocateSpace"
    const val PRIV_TEMPLATE = "Datastore.AllocateTemplate"

    /** Every storage on [node], enabled or not. */
    suspend fun list(client: PveClient, node: String): List<StorageInfo> = parseStorages(node, client.get("/nodes/$node/storage"))

    suspend fun content(client: PveClient, node: String, storage: String): List<ContentItem> =
        parseContent(client.get("/nodes/$node/storage/$storage/content"))

    /** Deletes a volume (a backup here). Returns a task UPID, or null when it finished right away. */
    suspend fun delete(client: PveClient, node: String, storage: String, volid: String): String? =
        (client.delete("/nodes/$node/storage/$storage/content/$volid") as? JsonPrimitive)?.content?.takeIf { it != "null" }

    suspend fun appliances(client: PveClient, node: String): List<Appliance> = parseAppliances(client.get("/nodes/$node/aplinfo"))

    /** Downloads a container template to [storage]. Returns the task UPID. */
    suspend fun downloadTemplate(client: PveClient, node: String, storage: String, template: String): String? =
        (client.post("/nodes/$node/aplinfo", mapOf("storage" to storage, "template" to template)) as? JsonPrimitive)?.content

    fun parseStorages(node: String, data: JsonElement?): List<StorageInfo> =
        (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            StorageInfo(
                node = node,
                id = o.str("storage") ?: return@mapNotNull null,
                type = o.str("type").orEmpty(),
                content = o.str("content").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                // "enabled" and "active" are 0/1; missing means the storage isn't usable right now.
                enabled = (o.long("enabled") ?: 1L) != 0L,
                active = (o.long("active") ?: 0L) != 0L,
                shared = (o.long("shared") ?: 0L) != 0L,
                total = o.long("total") ?: 0L,
                used = o.long("used") ?: 0L,
                avail = o.long("avail") ?: 0L,
            )
        }.sortedBy { it.id }

    fun parseContent(data: JsonElement?): List<ContentItem> =
        (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            ContentItem(
                volid = o.str("volid") ?: return@mapNotNull null,
                content = o.str("content").orEmpty(),
                ctime = o.long("ctime") ?: 0L,
                size = o.long("size") ?: 0L,
                format = o.str("format").orEmpty(),
                vmid = o.long("vmid")?.toInt()?.takeIf { it > 0 },
                notes = o.str("notes").orEmpty().trim(),
                isProtected = (o.long("protected") ?: 0L) != 0L,
                verification = (o["verification"] as? JsonObject)?.str("state"),
            )
        }

    fun parseAppliances(data: JsonElement?): List<Appliance> =
        (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            Appliance(
                template = o.str("template") ?: return@mapNotNull null,
                section = o.str("section").orEmpty(),
                os = o.str("os").orEmpty(),
                version = o.str("version").orEmpty(),
                headline = o.str("headline").orEmpty(),
                description = o.str("description").orEmpty().trim(),
            )
        }.sortedWith(compareBy({ it.section }, { it.template }))
}

/** A physical disk: GET /nodes/{node}/disks/list. */
data class DiskInfo(
    val devpath: String,
    /** hdd, ssd, nvme, usb, unknown. */
    val type: String,
    val size: Long,
    val model: String,
    val serial: String,
    val vendor: String,
    /** PASSED, OK, FAILED, UNKNOWN… */
    val health: String,
    /** Proxmox reports the remaining life in percent; the web UI shows 100 minus this as "Wearout". */
    val lifeLeft: Double?,
    /** What uses the disk: LVM, ZFS, partitions, mounted… */
    val usedBy: String,
    val rpm: Long?,
) {
    val wearoutPercent: Int? get() = lifeLeft?.let { (100 - it).toInt().coerceIn(0, 100) }
    val healthy: Boolean? get() = when (health.uppercase()) {
        "PASSED", "OK" -> true
        "UNKNOWN", "" -> null
        else -> false
    }
}

/** One S.M.A.R.T. attribute. */
data class SmartAttribute(val id: String, val name: String, val value: String, val worst: String, val threshold: String, val raw: String, val failing: String)

/** GET /nodes/{node}/disks/smart?disk=: attributes (ATA) or plain text (NVMe, SAS). */
data class SmartInfo(val health: String, val text: String?, val attributes: List<SmartAttribute>)

/** GET /nodes/{node}/disks/zfs: one pool. */
data class ZfsPool(val name: String, val size: Long, val alloc: Long, val free: Long, val frag: Long?, val dedup: Double?, val health: String) {
    val fraction: Double? get() = if (size > 0) alloc.toDouble() / size else null
}

/** One vdev or disk in a pool, with how deep it sits. */
data class ZfsDevice(val name: String, val state: String, val read: Long, val write: Long, val cksum: Long, val msg: String, val depth: Int)

/** GET /nodes/{node}/disks/zfs/{name}. */
data class ZfsDetail(
    val name: String,
    val state: String,
    val status: String,
    val action: String,
    val scan: String,
    val errors: String,
    val devices: List<ZfsDevice>,
)

/** An LVM volume group from GET /nodes/{node}/disks/lvm, with its physical volumes. */
data class VolumeGroup(val name: String, val size: Long, val free: Long, val pvs: List<Pair<String, Long>>) {
    val fraction: Double? get() = if (size > 0) (size - free).toDouble() / size else null
}

/** An LVM-thin pool: GET /nodes/{node}/disks/lvmthin. */
data class ThinPool(val lv: String, val vg: String, val size: Long, val used: Long, val metaSize: Long, val metaUsed: Long) {
    val fraction: Double? get() = if (size > 0) used.toDouble() / size else null
    val metaFraction: Double? get() = if (metaSize > 0) metaUsed.toDouble() / metaSize else null
}

/** Disks, ZFS and LVM. All need Sys.Audit (on / for SMART, ZFS and LVM). */
object DiskApi {

    suspend fun disks(client: PveClient, node: String): List<DiskInfo> = parseDisks(client.get("/nodes/$node/disks/list"))

    suspend fun smart(client: PveClient, node: String, devpath: String): SmartInfo =
        parseSmart(client.get("/nodes/$node/disks/smart", mapOf("disk" to devpath)))

    suspend fun zfsPools(client: PveClient, node: String): List<ZfsPool> = parseZfsPools(client.get("/nodes/$node/disks/zfs"))

    suspend fun zfsDetail(client: PveClient, node: String, name: String): ZfsDetail = parseZfsDetail(name, client.get("/nodes/$node/disks/zfs/$name"))

    suspend fun volumeGroups(client: PveClient, node: String): List<VolumeGroup> = parseLvm(client.get("/nodes/$node/disks/lvm"))

    suspend fun thinPools(client: PveClient, node: String): List<ThinPool> = parseThin(client.get("/nodes/$node/disks/lvmthin"))

    fun parseDisks(data: JsonElement?): List<DiskInfo> =
        (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            DiskInfo(
                devpath = o.str("devpath") ?: return@mapNotNull null,
                type = o.str("type").orEmpty(),
                size = o.long("size") ?: 0L,
                model = o.str("model").orEmpty().trim(),
                serial = o.str("serial").orEmpty().trim(),
                vendor = o.str("vendor").orEmpty().trim(),
                health = o.str("health").orEmpty(),
                // "N/A" when unknown.
                lifeLeft = o.num("wearout"),
                usedBy = o.str("used").orEmpty(),
                rpm = o.long("rpm")?.takeIf { it > 0 },
            )
        }.sortedBy { it.devpath }

    fun parseSmart(data: JsonElement?): SmartInfo {
        val o = data as? JsonObject ?: JsonObject(emptyMap())
        val attrs = (o["attributes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { a ->
            SmartAttribute(
                id = a.str("id").orEmpty(),
                name = a.str("name").orEmpty(),
                value = a.str("value").orEmpty(),
                worst = a.str("worst").orEmpty(),
                threshold = a.str("threshold").orEmpty(),
                raw = a.str("raw").orEmpty(),
                failing = a.str("fail").orEmpty(),
            )
        }
        return SmartInfo(
            health = o.str("health").orEmpty(),
            text = o.str("text")?.takeIf { o.str("type") == "text" || attrs.isEmpty() },
            attributes = attrs,
        )
    }

    fun parseZfsPools(data: JsonElement?): List<ZfsPool> =
        (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            ZfsPool(
                name = o.str("name") ?: return@mapNotNull null,
                size = o.long("size") ?: 0L,
                alloc = o.long("alloc") ?: 0L,
                free = o.long("free") ?: 0L,
                frag = o.long("frag"),
                dedup = o.num("dedup"),
                health = o.str("health").orEmpty(),
            )
        }.sortedBy { it.name }

    fun parseZfsDetail(name: String, data: JsonElement?): ZfsDetail {
        val o = data as? JsonObject ?: JsonObject(emptyMap())
        val devices = mutableListOf<ZfsDevice>()
        fun walk(list: JsonArray?, depth: Int) {
            list.orEmpty().mapNotNull { it as? JsonObject }.forEach { d ->
                devices += ZfsDevice(
                    name = d.str("name").orEmpty(),
                    state = d.str("state").orEmpty(),
                    read = d.long("read") ?: 0L,
                    write = d.long("write") ?: 0L,
                    cksum = d.long("cksum") ?: 0L,
                    msg = d.str("msg").orEmpty().trim(),
                    depth = depth,
                )
                walk(d["children"] as? JsonArray, depth + 1)
            }
        }
        walk(o["children"] as? JsonArray, 0)
        return ZfsDetail(
            name = o.str("name") ?: name,
            state = o.str("state").orEmpty(),
            status = o.str("status").orEmpty().trim(),
            action = o.str("action").orEmpty().trim(),
            scan = o.str("scan").orEmpty().trim(),
            errors = o.str("errors").orEmpty().trim(),
            devices = devices,
        )
    }

    fun parseLvm(data: JsonElement?): List<VolumeGroup> {
        val root = data as? JsonObject ?: return emptyList()
        return (root["children"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { vg ->
            VolumeGroup(
                name = vg.str("name") ?: return@mapNotNull null,
                size = vg.long("size") ?: 0L,
                free = vg.long("free") ?: 0L,
                pvs = (vg["children"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { pv ->
                    (pv.str("name") ?: return@mapNotNull null) to (pv.long("size") ?: 0L)
                },
            )
        }.sortedBy { it.name }
    }

    fun parseThin(data: JsonElement?): List<ThinPool> =
        (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            ThinPool(
                lv = o.str("lv") ?: return@mapNotNull null,
                vg = o.str("vg").orEmpty(),
                size = o.long("lv_size") ?: 0L,
                used = o.long("used") ?: 0L,
                metaSize = o.long("metadata_size") ?: 0L,
                metaUsed = o.long("metadata_used") ?: 0L,
            )
        }.sortedWith(compareBy({ it.vg }, { it.lv }))
}
