package com.vknxworks.proxctrl.data.guests

import com.vknxworks.proxctrl.data.api.PveClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One entry of GET {base}/snapshot. The entry named "current" is where the guest is now. */
data class Snapshot(
    val name: String,
    val description: String,
    /** Seconds since the epoch; null for "current". */
    val time: Long?,
    val parent: String?,
    /** VMs: the snapshot includes RAM, so rolling back resumes the VM where it was. */
    val withRam: Boolean,
) {
    val isCurrent: Boolean get() = name == CURRENT

    companion object {
        const val CURRENT = "current"
    }
}

/** A snapshot placed in the tree: how deep it sits below the oldest snapshot. */
data class SnapshotNode(val snapshot: Snapshot, val depth: Int)

/**
 * Snapshots. Listing needs VM.Audit; taking and deleting need VM.Snapshot; rolling back needs
 * VM.Snapshot or VM.Snapshot.Rollback (all on /vms/{vmid}). Every write returns a task UPID.
 */
object SnapshotApi {
    const val PRIV = "VM.Snapshot"
    const val PRIV_ROLLBACK = "VM.Snapshot.Rollback"

    /** Proxmox snapshot names: a letter, then letters, digits, "_" or "-"; 2 to 40 characters. */
    val NAME = Regex("^[A-Za-z][A-Za-z0-9_-]{1,39}$")

    fun isValidName(name: String): Boolean = NAME.matches(name) && name != Snapshot.CURRENT

    suspend fun list(client: PveClient, ref: GuestRef): List<Snapshot> = parse(client.get("${ref.base}/snapshot"))

    suspend fun take(client: PveClient, ref: GuestRef, name: String, description: String, withRam: Boolean): String? {
        val params = buildMap {
            put("snapname", name)
            if (description.isNotBlank()) put("description", description)
            if (withRam && ref.type == GuestType.QEMU) put("vmstate", "1")
        }
        return (client.post("${ref.base}/snapshot", params) as? JsonPrimitive)?.content
    }

    suspend fun rollback(client: PveClient, ref: GuestRef, name: String, start: Boolean): String? {
        val params = if (start) mapOf("start" to "1") else emptyMap()
        return (client.post("${ref.base}/snapshot/$name/rollback", params) as? JsonPrimitive)?.content
    }

    suspend fun delete(client: PveClient, ref: GuestRef, name: String): String? =
        (client.delete("${ref.base}/snapshot/$name") as? JsonPrimitive)?.content

    fun parse(data: JsonElement?): List<Snapshot> {
        val list = data as? JsonArray ?: return emptyList()
        return list.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val name = o.str("name") ?: return@mapNotNull null
            Snapshot(
                name = name,
                description = o.str("description").orEmpty().trim(),
                time = o.long("snaptime"),
                parent = o.str("parent")?.takeIf { it.isNotBlank() },
                withRam = (o.long("vmstate") ?: 0L) != 0L,
            )
        }
    }

    /**
     * Orders snapshots as a tree, oldest first, each child below its parent; "current" comes last on
     * its branch. Snapshots whose parent is missing are treated as roots.
     */
    fun tree(snapshots: List<Snapshot>): List<SnapshotNode> {
        val byName = snapshots.associateBy { it.name }
        val children = snapshots.groupBy { s -> s.parent?.takeIf { it in byName && it != s.name } }
        val order = compareBy<Snapshot>({ it.isCurrent }, { it.time ?: Long.MAX_VALUE }, { it.name })
        val out = mutableListOf<SnapshotNode>()
        val seen = mutableSetOf<String>()
        fun visit(s: Snapshot, depth: Int) {
            if (!seen.add(s.name)) return
            out += SnapshotNode(s, depth)
            children[s.name].orEmpty().sortedWith(order).forEach { visit(it, depth + 1) }
        }
        children[null].orEmpty().sortedWith(order).forEach { visit(it, 0) }
        // Anything left over (a parent cycle): show it rather than hide it.
        snapshots.filter { it.name !in seen }.sortedWith(order).forEach { visit(it, 0) }
        return out
    }
}
