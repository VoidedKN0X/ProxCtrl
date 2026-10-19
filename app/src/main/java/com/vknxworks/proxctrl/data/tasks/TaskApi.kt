package com.vknxworks.proxctrl.data.tasks

import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.guests.long
import com.vknxworks.proxctrl.data.guests.str
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Where a task stands: GET /nodes/{node}/tasks/{upid}/status. */
data class TaskState(
    val running: Boolean,
    /** "OK", "WARNINGS: n", or the error message; null while running. */
    val exitStatus: String?,
) {
    val ok: Boolean get() = !running && (exitStatus == "OK" || exitStatus?.startsWith("WARNINGS") == true)
    val warnings: Boolean get() = exitStatus?.startsWith("WARNINGS") == true
}

/**
 * Proxmox tasks (UPIDs). A UPID looks like "UPID:VKLab:000A1B2C:0123ABCD:6710F00D:vzdump:106:root@pam:";
 * its second field is the node the task runs on. Reading your own tasks needs no extra privilege.
 */
object TaskApi {

    fun nodeOf(upid: String): String? = upid.split(':').getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun base(upid: String): String {
        val node = nodeOf(upid) ?: throw PveError.NotProxmox(null)
        return "/nodes/$node/tasks/$upid"
    }

    suspend fun state(client: PveClient, upid: String): TaskState {
        val o = client.get("${base(upid)}/status") as? JsonObject ?: JsonObject(emptyMap())
        val running = o.str("status") == "running"
        return TaskState(running, if (running) null else o.str("exitstatus") ?: "unknown")
    }

    /** The last [lines] lines of the task log. */
    suspend fun logTail(client: PveClient, upid: String, lines: Int = 12): List<String> {
        val list = client.get("${base(upid)}/log", mapOf("start" to "0", "limit" to "5000")) as? JsonArray ?: return emptyList()
        return list.mapNotNull { (it as? JsonObject)?.let { o -> (o.long("n") ?: 0L) to o.str("t").orEmpty() } }
            .sortedBy { it.first }
            .map { it.second }
            .filter { it.isNotBlank() && it != "no content" }
            .takeLast(lines)
    }

    /**
     * Waits until the task has finished, checking every [intervalMs]. Network hiccups while waiting
     * are retried a few times rather than reported as a failed task.
     */
    suspend fun await(client: PveClient, upid: String, intervalMs: Long = 1_500, onCheck: (TaskState) -> Unit = {}): TaskState {
        var failures = 0
        while (true) {
            val state = try {
                state(client, upid).also { failures = 0 }
            } catch (e: PveError) {
                if (++failures >= 5) throw e
                null
            }
            if (state != null) {
                onCheck(state)
                if (!state.running) return state
            }
            delay(intervalMs)
        }
    }
}
