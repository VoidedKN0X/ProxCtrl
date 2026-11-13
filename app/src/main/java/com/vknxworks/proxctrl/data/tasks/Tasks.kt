package com.vknxworks.proxctrl.data.tasks

import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.guests.long
import com.vknxworks.proxctrl.data.guests.str
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One task, from GET /nodes/{node}/tasks. Proxmox splits a token's tasks into [user] and [tokenId].
 * [status] is "OK", "WARNINGS: n", an error message, or "RUNNING" for an active task.
 */
data class ProxTask(
    val upid: String,
    val node: String,
    val type: String,
    val id: String,
    val user: String,
    val tokenId: String?,
    val status: String,
    /** Seconds since the epoch. */
    val start: Long,
    /** Seconds since the epoch; 0 while running or unknown. */
    val end: Long,
) {
    val isRunning: Boolean get() = status == "RUNNING" || (status.isBlank() && end == 0L)
    val isOk: Boolean get() = status == "OK"
    val hasWarnings: Boolean get() = status.startsWith("WARNINGS")
    val isError: Boolean get() = !isRunning && !isOk && !hasWarnings

    /** "user@realm" or "user@realm!token", as Proxmox shows it. */
    val fullUser: String get() = if (tokenId.isNullOrBlank()) user else "$user!$tokenId"

    /** The guest the task is about, when its ID is a VMID. */
    val vmid: Int? get() = id.toIntOrNull()?.takeIf { it >= 100 }

    /** Same rule as Proxmox: the task's own user (or a user's token's tasks) may stop it without Sys.Modify. */
    fun isOwnedBy(authId: String): Boolean = authId == fullUser || (tokenId != null && authId == user)

    /** Seconds the task ran (or has been running, from [now]). */
    fun duration(now: Long = System.currentTimeMillis() / 1000): Long =
        ((if (end > 0) end else now) - start).coerceAtLeast(0)
}

/** Which tasks the history shows. Status maps to Proxmox's statusfilter; type and vmid are exact; user is a substring. */
data class TaskFilter(
    val status: StatusFilter = StatusFilter.ALL,
    val type: String? = null,
    val user: String? = null,
    val vmid: Int? = null,
) {
    val isEmpty: Boolean get() = status == StatusFilter.ALL && type == null && user == null && vmid == null
}

enum class StatusFilter(val api: String?) { ALL(null), OK("ok"), WARNING("warning"), ERROR("error") }

/** A UPID taken apart: "UPID:node:pid:pstart:starttime:type:id:user:". */
data class Upid(val node: String, val start: Long, val type: String, val id: String, val user: String)

/** One line of a task log; [n] counts from 1. */
data class LogLine(val n: Long, val text: String)

/**
 * Task lists, logs and stopping. Everyone sees their own tasks; Sys.Audit on /nodes/{node} shows all
 * tasks of that node. Stopping someone else's task needs Sys.Modify on the node.
 */
object TaskList {
    const val STOP_PRIV = "Sys.Modify"
    const val AUDIT_PRIV = "Sys.Audit"

    private val UPID_RE = Regex("^UPID:([A-Za-z0-9][A-Za-z0-9-]*):([0-9A-Fa-f]{8,9}):([0-9A-Fa-f]{8,9}):([0-9A-Fa-f]{8}):([^:\\s]+):([^:\\s]*):([^:\\s]+):$")

    fun decode(upid: String): Upid? {
        val m = UPID_RE.matchEntire(upid.trim()) ?: return null
        val g = m.groupValues
        return Upid(node = g[1], start = g[4].toLong(16), type = g[5], id = g[6], user = g[7])
    }

    /** Online nodes, or every node when none reports a status. */
    suspend fun nodes(client: PveClient): List<String> {
        val list = (client.get("/nodes") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val all = list.mapNotNull { o -> o.str("node")?.let { it to o.str("status") } }
        val online = all.filter { it.second == null || it.second == "online" }.map { it.first }
        return online.ifEmpty { all.map { it.first } }.sorted()
    }

    /** Tasks running now on [nodes], newest first. */
    suspend fun running(client: PveClient, nodes: List<String>): List<ProxTask> = coroutineScope {
        nodes.map { n ->
            async { runCatching { parse(client.get("/nodes/$n/tasks", mapOf("source" to "active", "limit" to "200")), n) }.getOrDefault(emptyList()) }
        }.flatMap { it.await() }.filter { it.isRunning }.sortedByDescending { it.start }
    }

    /**
     * Finished tasks on [nodes] matching [filter], newest first, up to [limit] per node. A node that
     * can't be read is skipped unless every node fails.
     */
    suspend fun history(client: PveClient, nodes: List<String>, filter: TaskFilter, limit: Int): List<ProxTask> = coroutineScope {
        val query = buildMap {
            put("start", "0")
            put("limit", limit.toString())
            filter.status.api?.let { put("statusfilter", it) }
            filter.type?.let { put("typefilter", it) }
            filter.user?.takeIf { it.isNotBlank() }?.let { put("userfilter", it) }
            filter.vmid?.let { put("vmid", it.toString()) }
        }
        val results = nodes.map { n -> async { runCatching { parse(client.get("/nodes/$n/tasks", query), n) } } }.map { it.await() }
        if (results.isNotEmpty() && results.all { it.isFailure }) throw results.first().exceptionOrNull() as? PveError ?: PveError.NotProxmox(null)
        results.flatMap { it.getOrNull().orEmpty() }.sortedByDescending { it.start }
    }

    /**
     * Finds one task in the node's list by its start time and type, to learn its end time and final
     * status (the status endpoint has neither).
     */
    suspend fun find(client: PveClient, upid: String): ProxTask? {
        val u = decode(upid) ?: return null
        val query = mapOf(
            "since" to u.start.toString(),
            "until" to u.start.toString(),
            "typefilter" to u.type,
            "limit" to "50",
            "source" to "all",
        )
        return parse(client.get("/nodes/${u.node}/tasks", query), u.node).firstOrNull { it.upid == upid }
    }

    /** DELETE /nodes/{node}/tasks/{upid}: asks Proxmox to stop the task. */
    suspend fun stop(client: PveClient, upid: String) {
        val node = TaskApi.nodeOf(upid) ?: throw PveError.NotProxmox(null)
        client.delete("/nodes/$node/tasks/$upid")
    }

    /**
     * Log lines after line [after] (0 for the start), at most [limit]. Proxmox answers an empty log with
     * a single "no content" line; that placeholder is dropped so it isn't counted as a real line.
     */
    suspend fun log(client: PveClient, upid: String, after: Long, limit: Int = 500): List<LogLine> {
        val node = TaskApi.nodeOf(upid) ?: throw PveError.NotProxmox(null)
        val list = (client.get("/nodes/$node/tasks/$upid/log", mapOf("start" to after.toString(), "limit" to limit.toString())) as? JsonArray).orEmpty()
        val lines = list.mapNotNull { it as? JsonObject }.mapNotNull { o -> o.long("n")?.let { LogLine(it, o.str("t").orEmpty()) } }
            .filter { it.n > after }
            .sortedBy { it.n }
        return if (after == 0L && lines.size == 1 && lines[0].n == 1L && lines[0].text == "no content") emptyList() else lines
    }

    fun parse(data: JsonElement?, fallbackNode: String): List<ProxTask> =
        (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val upid = o.str("upid") ?: return@mapNotNull null
            val decoded = decode(upid)
            ProxTask(
                upid = upid,
                node = o.str("node") ?: decoded?.node ?: fallbackNode,
                type = o.str("type") ?: decoded?.type.orEmpty(),
                id = o.str("id") ?: decoded?.id.orEmpty(),
                user = o.str("user") ?: decoded?.user.orEmpty(),
                tokenId = o.str("tokenid")?.takeIf { it.isNotBlank() },
                status = o.str("status").orEmpty(),
                start = o.long("starttime") ?: decoded?.start ?: 0L,
                end = o.long("endtime") ?: 0L,
            )
        }
}
