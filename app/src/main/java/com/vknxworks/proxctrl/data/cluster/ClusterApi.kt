package com.vknxworks.proxctrl.data.cluster

import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.guests.GuestRef
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.guests.long
import com.vknxworks.proxctrl.data.guests.str
import com.vknxworks.proxctrl.data.overview.HaEntry
import com.vknxworks.proxctrl.data.overview.OverviewParse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One member of the cluster, from GET /cluster/status. */
data class ClusterMember(val name: String, val online: Boolean, val local: Boolean, val ip: String?)

/** GET /cluster/status. [name] is null for a single node that isn't part of a cluster. */
data class ClusterState(val name: String?, val quorate: Boolean, val members: List<ClusterMember>)

/** A storage replication job and how its last run went: GET /nodes/{node}/replication. */
data class ReplicationJob(
    val id: String,
    val guest: Int?,
    val target: String,
    val schedule: String,
    /** Seconds since the epoch; 0 when it never ran. */
    val lastSync: Long,
    val nextSync: Long,
    val failCount: Int,
    val error: String?,
    val disabled: Boolean,
)

/** The parts of "ceph status" the app shows: GET /nodes/{node}/ceph/status. */
data class CephState(
    /** HEALTH_OK, HEALTH_WARN or HEALTH_ERR. */
    val health: String,
    val checks: List<String>,
    val usedBytes: Long,
    val totalBytes: Long,
    val osds: Int?,
    val osdsUp: Int?,
    val osdsIn: Int?,
)

/**
 * What only exists once there is more than one node: the cluster's members and quorum, high
 * availability, storage replication, Ceph, and moving a guest to another node. Everything here is
 * read-only except [migrate].
 */
object ClusterApi {
    const val PRIV_MIGRATE = "VM.Migrate"

    suspend fun state(client: PveClient): ClusterState = parseState(client.get("/cluster/status"))

    fun parseState(data: JsonElement?): ClusterState {
        val entries = (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val cluster = entries.firstOrNull { it.str("type") == "cluster" }
        return ClusterState(
            name = cluster?.str("name"),
            quorate = cluster?.let { flag(it, "quorate") } ?: true,
            members = entries.filter { it.str("type") == "node" }.mapNotNull { o ->
                ClusterMember(o.str("name") ?: return@mapNotNull null, flag(o, "online"), flag(o, "local"), o.str("ip"))
            }.sortedBy { it.name },
        )
    }

    private fun flag(o: JsonObject, key: String): Boolean = o.str(key)?.let { it == "1" || it == "true" } == true

    /** High availability: the manager, the nodes' resource managers and every HA-managed guest. */
    suspend fun ha(client: PveClient): List<HaEntry> = OverviewParse.ha(client.get("/cluster/ha/status/current"))

    suspend fun replication(client: PveClient, node: String): List<ReplicationJob> = parseReplication(client.get("/nodes/$node/replication"))

    fun parseReplication(data: JsonElement?): List<ReplicationJob> =
        (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            ReplicationJob(
                id = o.str("id") ?: return@mapNotNull null,
                guest = o.long("guest")?.toInt(),
                target = o.str("target").orEmpty(),
                schedule = o.str("schedule").orEmpty(),
                lastSync = o.long("last_sync") ?: 0L,
                nextSync = o.long("next_sync") ?: 0L,
                failCount = o.long("fail_count")?.toInt() ?: 0,
                error = o.str("error")?.takeIf { it.isNotBlank() },
                disabled = flag(o, "disable"),
            )
        }.sortedBy { it.id }

    /** Ceph's status. Fails (an error from Proxmox) on nodes where Ceph isn't set up. */
    suspend fun ceph(client: PveClient, node: String): CephState = parseCeph(client.get("/nodes/$node/ceph/status"))

    fun parseCeph(data: JsonElement?): CephState {
        val o = data as? JsonObject ?: JsonObject(emptyMap())
        val health = o["health"] as? JsonObject
        val checks = (health?.get("checks") as? JsonObject)?.entries.orEmpty().map { (name, v) ->
            val message = ((v as? JsonObject)?.get("summary") as? JsonObject)?.str("message")
            if (message.isNullOrBlank()) name else "$name: $message"
        }
        val pgmap = o["pgmap"] as? JsonObject
        // Newer Ceph has the OSD counts directly under "osdmap", older releases one level deeper.
        val osdmap = (o["osdmap"] as? JsonObject)?.let { (it["osdmap"] as? JsonObject) ?: it }
        return CephState(
            health = health?.str("status").orEmpty(),
            checks = checks,
            usedBytes = pgmap?.long("bytes_used") ?: 0L,
            totalBytes = pgmap?.long("bytes_total") ?: 0L,
            osds = osdmap?.long("num_osds")?.toInt(),
            osdsUp = osdmap?.long("num_up_osds")?.toInt(),
            osdsIn = osdmap?.long("num_in_osds")?.toInt(),
        )
    }

    /**
     * The form fields for moving a guest to [target]. A running VM moves live ("online"), with its
     * local disks when [withLocalDisks]; a running container can't move live, so it is shut down,
     * moved and started again ("restart"). A stopped guest just moves.
     */
    fun migrateParams(type: GuestType, target: String, running: Boolean, withLocalDisks: Boolean): Map<String, String> = buildMap {
        put("target", target)
        if (running) {
            when (type) {
                GuestType.QEMU -> {
                    put("online", "1")
                    if (withLocalDisks) put("with-local-disks", "1")
                }
                GuestType.LXC -> put("restart", "1")
            }
        }
    }

    /** POST {base}/migrate (VM.Migrate on /vms/{vmid}); returns the task's UPID. */
    suspend fun migrate(client: PveClient, ref: GuestRef, target: String, running: Boolean, withLocalDisks: Boolean): String? =
        (client.post("${ref.base}/migrate", migrateParams(ref.type, target, running, withLocalDisks)) as? JsonPrimitive)?.content
}
