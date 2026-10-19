package com.vknxworks.proxctrl.data.storage

import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.guests.long
import com.vknxworks.proxctrl.data.guests.str
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Which guests a backup job covers. */
sealed interface JobSelection {
    data object All : JobSelection
    data class AllExcept(val vmids: List<String>) : JobSelection
    data class Guests(val vmids: List<String>) : JobSelection
    data class Pool(val pool: String) : JobSelection
}

/** A scheduled backup job: GET /cluster/backup (needs Sys.Audit on /). */
data class BackupJob(
    val id: String,
    val enabled: Boolean,
    val schedule: String,
    /** Seconds since the epoch, when Proxmox could compute it (enabled jobs only). */
    val nextRun: Long?,
    val storage: String,
    /** The node the job is limited to, or null for every node. */
    val node: String?,
    val selection: JobSelection,
    val mode: String,
    val comment: String,
    /** The job's options, sent as they are to vzdump for "Run now", as the web UI does. */
    val options: Map<String, String>,
)

/** The newest vzdump task that looks like a run of a job. */
data class JobRun(val upid: String, val node: String, val start: Long, val end: Long, val status: String) {
    val running: Boolean get() = end == 0L && (status.isBlank() || status == "RUNNING")
    val ok: Boolean get() = status == "OK"
    val warnings: Boolean get() = status.startsWith("WARNINGS")
}

object BackupJobApi {

    /**
     * vzdump options a job can carry ("Run now" passes them on). Scheduling fields (id, schedule,
     * enabled, comment, node, next-run…) are left out, as in the web UI; job-id is root-only.
     */
    private val VZDUMP_OPTIONS = setOf(
        "vmid", "all", "stdexcludes", "compress", "pigz", "zstd", "quiet", "mode", "exclude", "exclude-path",
        "mailto", "mailnotification", "notification-mode", "tmpdir", "dumpdir", "script", "storage", "bwlimit",
        "ionice", "performance", "fleecing", "lockwait", "stopwait", "prune-backups", "remove", "pool",
        "notes-template", "protected", "pbs-change-detection-mode",
    )

    suspend fun list(client: PveClient): List<BackupJob> = parse(client.get("/cluster/backup"))

    /**
     * Starts [job] now on [nodes] (its own node when it has one): POST /nodes/{node}/vzdump with the
     * job's options, exactly like "Run now" in the web UI. Returns the UPIDs started.
     */
    suspend fun runNow(client: PveClient, job: BackupJob, nodes: List<String>): List<String> {
        val targets = job.node?.let { listOf(it) } ?: nodes
        return targets.mapNotNull { n -> (client.post("/nodes/$n/vzdump", job.options) as? JsonPrimitive)?.content?.takeIf { it.startsWith("UPID:") } }
    }

    /**
     * Last run of each job. Proxmox doesn't link tasks to jobs in the API (the job ID isn't in the
     * task), so this matches the newest vzdump tasks by the command line they log first
     * ("starting new backup job: vzdump 100 101 --storage pbs …"): same guests and same storage.
     */
    suspend fun lastRuns(client: PveClient, nodes: List<String>, jobs: List<BackupJob>): Map<String, JobRun> = coroutineScope {
        if (jobs.isEmpty()) return@coroutineScope emptyMap()
        val tasks = nodes.map { n ->
            async {
                runCatching { client.get("/nodes/$n/tasks", mapOf("typefilter" to "vzdump", "limit" to "25")) }.getOrNull()
                    .let { data -> (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { n to it } }
            }
        }.awaitAll().flatten()
            .sortedByDescending { (_, t) -> t.long("starttime") ?: 0L }
            .take(30)
        val found = mutableMapOf<String, JobRun>()
        for ((node, t) in tasks) {
            if (found.size == jobs.size) break
            val upid = t.str("upid") ?: continue
            val line = runCatching { firstLogLine(client, node, upid) }.getOrNull() ?: continue
            val cmd = parseCommand(line) ?: continue
            val job = jobs.firstOrNull { it.id !in found && matches(it, cmd) } ?: continue
            found[job.id] = JobRun(
                upid = upid,
                node = node,
                start = t.long("starttime") ?: 0L,
                end = t.long("endtime") ?: 0L,
                status = t.str("status").orEmpty(),
            )
        }
        found
    }

    private suspend fun firstLogLine(client: PveClient, node: String, upid: String): String? {
        val log = client.get("/nodes/$node/tasks/$upid/log", mapOf("start" to "0", "limit" to "5")) as? JsonArray ?: return null
        return log.mapNotNull { (it as? JsonObject)?.str("t") }.firstOrNull { it.contains("starting new backup job:") }
    }

    /** What a logged vzdump command line backs up, and where to. */
    data class Command(val vmids: Set<String>, val all: Boolean, val pool: String?, val storage: String?)

    fun parseCommand(line: String): Command? {
        val cmd = line.substringAfter("starting new backup job:", "").trim()
        if (!cmd.startsWith("vzdump")) return null
        val tokens = cmd.removePrefix("vzdump").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val vmids = mutableSetOf<String>()
        var all = false
        var pool: String? = null
        var storage: String? = null
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.startsWith("--")) {
                val value = tokens.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }?.trim('\'')
                when (t.removePrefix("--")) {
                    "all" -> all = value != "0"
                    "pool" -> pool = value
                    "storage" -> storage = value
                }
                i += if (value != null) 2 else 1
            } else {
                if (t.all { it.isDigit() }) vmids += t
                i++
            }
        }
        return Command(vmids, all, pool, storage)
    }

    private fun matches(job: BackupJob, cmd: Command): Boolean {
        if (job.storage.isNotBlank() && cmd.storage != null && job.storage != cmd.storage) return false
        return when (val s = job.selection) {
            JobSelection.All, is JobSelection.AllExcept -> cmd.all
            is JobSelection.Pool -> cmd.pool == s.pool
            is JobSelection.Guests -> !cmd.all && cmd.vmids == s.vmids.toSet()
        }
    }

    fun parse(data: JsonElement?): List<BackupJob> =
        (data as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            val vmids = o.str("vmid").orEmpty().split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
            val exclude = o.str("exclude").orEmpty().split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
            val all = o.str("all")?.let { it == "1" || it == "true" } == true
            val pool = o.str("pool")?.takeIf { it.isNotBlank() }
            val selection = when {
                pool != null -> JobSelection.Pool(pool)
                all && exclude.isNotEmpty() -> JobSelection.AllExcept(exclude)
                all -> JobSelection.All
                else -> JobSelection.Guests(vmids)
            }
            BackupJob(
                id = id,
                enabled = o.str("enabled")?.let { it != "0" && it != "false" } ?: true,
                schedule = o.str("schedule") ?: listOfNotNull(o.str("dow"), o.str("starttime")).joinToString(" "),
                nextRun = o.long("next-run"),
                storage = o.str("storage").orEmpty(),
                node = o.str("node")?.takeIf { it.isNotBlank() },
                selection = selection,
                mode = o.str("mode").orEmpty(),
                comment = o.str("comment").orEmpty().trim(),
                options = optionsFrom(o),
            )
        }.sortedBy { it.id }

    /** The job's vzdump options as form values; nested ones (performance, prune-backups…) as "k=v,k=v". */
    private fun optionsFrom(o: JsonObject): Map<String, String> = buildMap {
        for ((key, value) in o) {
            if (key !in VZDUMP_OPTIONS) continue
            val text = when (value) {
                is JsonObject -> value.entries.joinToString(",") { (k, v) -> "$k=${(v as? JsonPrimitive)?.content ?: v.toString()}" }
                // A list of paths is a NUL-separated list in Proxmox's form encoding.
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString("\u0000")
                is JsonPrimitive -> when (value.content) {
                    "true" -> "1"
                    "false" -> "0"
                    else -> value.content
                }
                else -> continue
            }
            if (text.isNotEmpty() && text != "null") put(key, text)
        }
    }
}
