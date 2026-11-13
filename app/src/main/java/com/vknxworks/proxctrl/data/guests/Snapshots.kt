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

/** Why a snapshot name is refused (see SnapshotApi.nameProblem). */
enum class SnapshotNameProblem { EMPTY, TOO_SHORT, TOO_LONG, FIRST_NOT_LETTER, BAD_CHARACTER, RESERVED, TAKEN }

/** A snapshot placed in the tree: how deep it sits below the oldest snapshot. */
data class SnapshotNode(val snapshot: Snapshot, val depth: Int)

/**
 * Snapshots. Listing needs VM.Audit; taking and deleting need VM.Snapshot; rolling back needs
 * VM.Snapshot or VM.Snapshot.Rollback (all on /vms/{vmid}). Every write returns a task UPID.
 */
object SnapshotApi {
    const val PRIV = "VM.Snapshot"
    const val PRIV_ROLLBACK = "VM.Snapshot.Rollback"

    /** Listing snapshots: VM.Audit on /vms/{vmid}. */
    const val PRIV_LIST = "VM.Audit"

    /**
     * Proxmox snapshot names ("pve-configid", maxLength 40 in pve-common's PVE::JSONSchema): a letter,
     * then letters, digits or "_" (no "-", no dots, no spaces); 2 to 40 characters.
     */
    val NAME = Regex("^[A-Za-z][A-Za-z0-9_]{1,39}$")

    const val NAME_MAX = 40

    /** Why a snapshot name can't be used, or null when it can. [existing]: the guest's snapshot names. */
    fun nameProblem(name: String, existing: Collection<String> = emptyList()): SnapshotNameProblem? = when {
        name.isEmpty() -> SnapshotNameProblem.EMPTY
        name.length > NAME_MAX -> SnapshotNameProblem.TOO_LONG
        !name.first().isAsciiLetter() -> SnapshotNameProblem.FIRST_NOT_LETTER
        name.any { !(it.isAsciiLetter() || it in '0'..'9' || it == '_') } -> SnapshotNameProblem.BAD_CHARACTER
        name.length < 2 -> SnapshotNameProblem.TOO_SHORT
        // qemu-server and pve-container refuse these two ("reserved name").
        name == Snapshot.CURRENT || name.equals("pending", ignoreCase = true) -> SnapshotNameProblem.RESERVED
        name in existing -> SnapshotNameProblem.TAKEN
        else -> null
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    fun isValidName(name: String): Boolean = nameProblem(name) == null

    // --- Requests (paths and form bodies), kept apart so they can be unit-tested ---

    fun listPath(ref: GuestRef) = "${ref.base}/snapshot"

    /** POST {base}/snapshot: snapname, description (when given), vmstate=1 to include RAM (running VMs only). */
    fun takeParams(ref: GuestRef, name: String, description: String, withRam: Boolean): Map<String, String> = buildMap {
        put("snapname", name)
        if (description.isNotBlank()) put("description", description.trim())
        if (withRam && ref.type == GuestType.QEMU) put("vmstate", "1")
    }

    /** POST {base}/snapshot/{name}/rollback; start=1 starts the guest afterwards (PVE 7.2+ for VMs, 8 for containers). */
    fun rollbackPath(ref: GuestRef, name: String) = "${ref.base}/snapshot/$name/rollback"

    fun rollbackParams(start: Boolean): Map<String, String> = if (start) mapOf("start" to "1") else emptyMap()

    /** DELETE {base}/snapshot/{name} */
    fun deletePath(ref: GuestRef, name: String) = "${ref.base}/snapshot/$name"

    suspend fun list(client: PveClient, ref: GuestRef): List<Snapshot> = parse(client.get(listPath(ref)))

    suspend fun take(client: PveClient, ref: GuestRef, name: String, description: String, withRam: Boolean): String? =
        (client.post(listPath(ref), takeParams(ref, name, description, withRam)) as? JsonPrimitive)?.content

    suspend fun rollback(client: PveClient, ref: GuestRef, name: String, start: Boolean): String? =
        (client.post(rollbackPath(ref, name), rollbackParams(start)) as? JsonPrimitive)?.content

    suspend fun delete(client: PveClient, ref: GuestRef, name: String): String? =
        (client.delete(deletePath(ref, name)) as? JsonPrimitive)?.content

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
