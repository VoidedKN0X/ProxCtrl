package com.vknxworks.proxctrl.data.guests

import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A storage from GET /nodes/{node}/storage (only those the user may see are listed). */
data class StorageChoice(
    val id: String,
    val type: String,
    val active: Boolean,
    val avail: Long,
    val total: Long,
) {
    /** Proxmox Backup Server: compresses and deduplicates itself, so there's no compression choice. */
    val isPbs: Boolean get() = type == "pbs"
}

/** One backup of a guest: an entry of GET /nodes/{node}/storage/{storage}/content?content=backup. */
data class Backup(
    val volid: String,
    val storage: String,
    /** Seconds since the epoch. */
    val time: Long,
    val size: Long,
    /** vma.zst, tar.zst, pbs-vm, pbs-ct… */
    val format: String,
    val notes: String,
    val isProtected: Boolean,
    /** "ok" or "failed" when the backup was verified (Proxmox Backup Server), else null. */
    val verification: String?,
) {
    /** The file or snapshot name without the storage prefix. */
    val fileName: String get() = volid.substringAfter(':').substringAfterLast('/')
}

/** Backup mode for vzdump. */
enum class BackupMode(val api: String) { SNAPSHOT("snapshot"), SUSPEND("suspend"), STOP("stop") }

/** Compression for vzdump on file storages ("0" is none). */
enum class Compression(val api: String) { ZSTD("zstd"), LZO("lzo"), GZIP("gzip"), NONE("0") }

/** One storage that couldn't be read while listing backups. */
data class StorageFailure(val storage: String, val error: PveError)

data class BackupList(val backups: List<Backup>, val failures: List<StorageFailure>)

/**
 * Backups of one guest. Listing needs Datastore.Audit or Datastore.AllocateSpace on each storage (plus
 * VM.Backup to see the guest's backups); backing up needs VM.Backup on the guest and
 * Datastore.AllocateSpace on the storage. Restoring over the existing guest needs VM.Backup and the
 * guest must be stopped; deleting a backup needs VM.Backup and Datastore.AllocateSpace.
 */
object BackupApi {
    const val PRIV = "VM.Backup"
    const val STORAGE_PRIV = "Datastore.AllocateSpace"

    /** Enabled storages on [node] that can hold [content] ("backup", "rootdir", "images"). */
    suspend fun storages(client: PveClient, node: String, content: String): List<StorageChoice> {
        val list = client.get("/nodes/$node/storage", mapOf("content" to content, "enabled" to "1")) as? JsonArray ?: return emptyList()
        return list.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            StorageChoice(
                id = o.str("storage") ?: return@mapNotNull null,
                type = o.str("type").orEmpty(),
                active = (o.long("active") ?: 1L) != 0L,
                avail = o.long("avail") ?: 0L,
                total = o.long("total") ?: 0L,
            )
        }.sortedBy { it.id }
    }

    /** Every backup of [ref] on the backup storages of its node, newest first. */
    suspend fun list(client: PveClient, ref: GuestRef): BackupList = coroutineScope {
        val stores = storages(client, ref.node, "backup").filter { it.active }
        val results = stores.map { s ->
            async {
                s.id to runCatching {
                    parse(client.get("/nodes/${ref.node}/storage/${s.id}/content", mapOf("content" to "backup", "vmid" to ref.vmid.toString())), s.id, ref.vmid)
                }
            }
        }.map { it.await() }
        BackupList(
            backups = results.flatMap { (_, r) -> r.getOrNull().orEmpty() }.sortedByDescending { it.time },
            failures = results.mapNotNull { (id, r) -> (r.exceptionOrNull() as? PveError)?.let { StorageFailure(id, it) } },
        )
    }

    /** POST /nodes/{node}/vzdump for one guest. Older backups are never pruned from here. */
    suspend fun backupNow(
        client: PveClient,
        ref: GuestRef,
        storage: StorageChoice,
        mode: BackupMode,
        compression: Compression,
        notes: String,
        protect: Boolean,
    ): String? {
        val params = buildMap {
            put("vmid", ref.vmid.toString())
            put("storage", storage.id)
            put("mode", mode.api)
            put("remove", "0")
            if (!storage.isPbs) put("compress", compression.api)
            // Notes are a template: newlines and backslashes must be escaped, as the web UI does.
            if (notes.isNotBlank()) put("notes-template", notes.trim().replace("\\", "\\\\").replace("\n", "\\n"))
            if (protect) put("protected", "1")
        }
        return (client.post("/nodes/${ref.node}/vzdump", params) as? JsonPrimitive)?.content
    }

    /**
     * Restores [backup] over the guest it belongs to (force=1). VMs keep the disks' storages from the
     * backup unless [storage] is given; containers need a storage for their root disk.
     */
    suspend fun restore(client: PveClient, ref: GuestRef, backup: Backup, storage: String?, start: Boolean): String? {
        val params = buildMap {
            put("vmid", ref.vmid.toString())
            put("force", "1")
            if (!storage.isNullOrBlank()) put("storage", storage)
            if (start) put("start", "1")
            when (ref.type) {
                GuestType.QEMU -> put("archive", backup.volid)
                GuestType.LXC -> {
                    put("ostemplate", backup.volid)
                    put("restore", "1")
                }
            }
        }
        return (client.post("/nodes/${ref.node}/${ref.type.api}", params) as? JsonPrimitive)?.content
    }

    /** Deletes a backup. Returns a task UPID (or null when Proxmox finished it straight away). */
    suspend fun delete(client: PveClient, node: String, backup: Backup): String? =
        (client.delete("/nodes/$node/storage/${backup.storage}/content/${backup.volid}") as? JsonPrimitive)?.content

    /** The storage a container's root disk is on, from "rootfs: local-zfs:subvol-100-disk-0,size=8G". */
    fun rootfsStorage(rootfs: String?): String? =
        rootfs?.substringBefore(',')?.substringBefore(':', "")?.takeIf { it.isNotBlank() }

    fun parse(data: JsonElement?, storage: String, vmid: Int): List<Backup> {
        val list = data as? JsonArray ?: return emptyList()
        return list.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val volid = o.str("volid") ?: return@mapNotNull null
            if (o.str("content")?.let { it != "backup" } == true) return@mapNotNull null
            // vmid is a filter on the server, but older versions ignore it for some storage types.
            val owner = o.long("vmid")?.toInt()
            if (owner != null && owner != vmid) return@mapNotNull null
            Backup(
                volid = volid,
                storage = storage,
                time = o.long("ctime") ?: 0L,
                size = o.long("size") ?: 0L,
                format = o.str("format").orEmpty(),
                notes = o.str("notes").orEmpty().trim(),
                isProtected = (o.long("protected") ?: 0L) != 0L,
                verification = (o["verification"] as? JsonObject)?.str("state"),
            )
        }
    }
}
