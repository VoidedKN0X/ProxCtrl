package com.vknxworks.proxctrl.data.notify

import android.content.Context
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.api.PveJson
import com.vknxworks.proxctrl.data.guests.ClusterResource
import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.guests.GuestApi
import com.vknxworks.proxctrl.data.guests.GuestRepository
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.guests.str
import com.vknxworks.proxctrl.data.overview.Attention
import com.vknxworks.proxctrl.data.overview.OverviewParse
import com.vknxworks.proxctrl.data.overview.Severity
import com.vknxworks.proxctrl.data.overview.StorageEntry
import com.vknxworks.proxctrl.data.overview.TaskEntry
import com.vknxworks.proxctrl.data.server.Server
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.components.percent
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** What a server looked like at the last check, so only changes are notified. Kept per server. */
@Serializable
data class CheckState(
    /** Conditions that were true last time (and have been notified). */
    val active: Set<String> = emptySet(),
    /** Guests that were running last time. */
    val running: Set<Int> = emptySet(),
    /** Tasks that ended before this (epoch seconds) have been looked at. 0 = never checked. */
    val tasksSeen: Long = 0,
    /** Checks in a row that couldn't reach the server. */
    val unreachableCount: Int = 0,
    val lastDigestAt: Long = 0,
    val lastCheckAt: Long = 0,
    val lastResult: String = "",
)

/** Result of one check: notifications to show, keys whose notifications to remove, and the new state. */
class CheckResult(val post: List<Alert>, val cancel: Set<String>, val state: CheckState)

data class CheckConfig(
    val notify: NotifySettings,
    val diskPercent: Int,
    val memPercent: Int,
    /** Whether multiple servers are saved; then titles name the server. */
    val manyServers: Boolean,
)

/**
 * One background check of one server. Conditions (disk, memory, CPU, storage, ZFS, SMART,
 * unreachable) are notified when they start and removed when they end; events (a guest that
 * stopped on its own, failed tasks and backups) are notified once. Everything is read in a few
 * calls: /cluster/resources for all guests, nodes and storage, plus per-node graphs, tasks and disks.
 */
class Checker(private val context: Context) {

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    suspend fun check(server: Server, client: PveClient, config: CheckConfig, prev: CheckState): CheckResult {
        val now = System.currentTimeMillis() / 1000
        val n = config.notify
        val prefix = if (config.manyServers) "${server.name}: " else ""
        val conditions = LinkedHashMap<String, Alert>()
        val events = mutableListOf<Alert>()

        // Pushes from Proxmox already cover backups and updates; don't notify them twice.
        val pushCovers = n.push

        val resources = try {
            client.get("/cluster/resources") as? JsonArray ?: JsonArray(emptyList())
        } catch (e: PveError) {
            return unreachable(server, config, prev, e, now)
        }

        val guests = mutableListOf<Guest>()
        val storages = mutableListOf<StorageEntry>()
        val nodes = mutableListOf<Pair<String, String>>()
        for (item in resources) {
            val o = item as? JsonObject ?: continue
            when (o.str("type")) {
                "qemu", "lxc" -> runCatching { PveJson.decodeFromJsonElement(ClusterResource.serializer(), o) }.getOrNull()?.let(Guest::from)?.let(guests::add)
                "storage" -> runCatching { PveJson.decodeFromJsonElement(StorageEntry.serializer(), o) }.getOrNull()?.let(storages::add)
                "node" -> nodes += (o.str("node").orEmpty() to o.str("status").orEmpty())
            }
        }
        val online = nodes.filter { it.second == "online" }.map { it.first }

        // --- Nodes offline (clusters) ---
        if (n.on(NotifyEvent.NODE_UNREACHABLE)) {
            nodes.filter { it.second != "online" && it.first.isNotBlank() }.forEach { (node, _) ->
                val key = "${server.id}:node-offline:$node"
                conditions[key] = Alert(key, NotifyEvent.NODE_UNREACHABLE, Severity.CRITICAL, prefix + s(R.string.att_node_offline, node), s(R.string.att_node_offline_detail))
            }
        }

        // --- Guest disks (VMs from the guest agent) ---
        if (n.on(NotifyEvent.GUEST_DISK)) {
            val withAgent = guests.map { g ->
                if (g.type == GuestType.QEMU && g.isRunning && !g.isTemplate) {
                    val d = runCatching { GuestRepository.parseFsInfo(client.get("/nodes/${g.node}/qemu/${g.vmid}/agent/get-fsinfo")) }.getOrNull()
                    if (d != null) g.copy(disk = d.used, maxDisk = d.total) else g
                } else {
                    g
                }
            }
            withAgent.filter { !it.isTemplate }.forEach { g ->
                val f = g.diskFraction ?: return@forEach
                val sev = Attention.severityFor(f, config.diskPercent) ?: return@forEach
                val name = "${g.vmid} ${g.name}".trim()
                levelKeys(server, "disk:${g.vmid}", sev).forEach { key ->
                    conditions[key] = Alert(
                        key, NotifyEvent.GUEST_DISK, sev,
                        prefix + s(R.string.att_guest_disk, name, percent(f)),
                        s(R.string.att_guest_disk_detail, formatBytes(g.disk), formatBytes(g.maxDisk)),
                        vmid = g.vmid,
                    )
                }
            }
        }

        // --- Storage ---
        if (n.on(NotifyEvent.STORAGE_FULL)) {
            storages.distinctBy { if (it.shared != 0) it.storage else "${it.node}/${it.storage}" }.forEach { st ->
                val f = st.fraction ?: return@forEach
                if (!st.isAvailable) return@forEach
                val sev = Attention.severityFor(f, config.diskPercent) ?: return@forEach
                levelKeys(server, "storage:${st.node}:${st.storage}", sev).forEach { key ->
                    conditions[key] = Alert(
                        key, NotifyEvent.STORAGE_FULL, sev,
                        prefix + s(R.string.att_storage_full, st.storage, percent(f)),
                        s(R.string.att_storage_detail, formatBytes(st.disk.toLong()), formatBytes(st.maxdisk.toLong()), st.node),
                    )
                }
            }
        }

        // --- Node CPU and memory, high for the whole last N minutes (1-minute graph points) ---
        if (n.on(NotifyEvent.NODE_CPU) || n.on(NotifyEvent.NODE_MEMORY)) {
            for (node in online) {
                val rrd = runCatching { GuestApi.parseRrd(client.get("/nodes/$node/rrddata", mapOf("timeframe" to "hour", "cf" to "AVERAGE"))) }.getOrNull() ?: continue
                val recentCpu = rrd.mapNotNull { it.values["cpu"] }.takeLast(n.sustainMinutes)
                if (n.on(NotifyEvent.NODE_CPU) && recentCpu.size >= n.sustainMinutes && recentCpu.all { it * 100 >= n.cpuPercent }) {
                    val key = "${server.id}:cpu:$node"
                    conditions[key] = Alert(
                        key, NotifyEvent.NODE_CPU, Severity.WARNING,
                        prefix + s(R.string.nt_cpu_title, node, percent(recentCpu.average())),
                        s(R.string.nt_sustained, n.sustainMinutes, n.cpuPercent),
                    )
                }
                val recentMem = rrd.mapNotNull { p ->
                    val used = p.values["memused"]
                    val total = p.values["memtotal"]
                    if (used != null && total != null && total > 0) used / total else null
                }.takeLast(n.sustainMinutes)
                if (n.on(NotifyEvent.NODE_MEMORY) && recentMem.size >= n.sustainMinutes && recentMem.all { it * 100 >= config.memPercent }) {
                    val key = "${server.id}:mem:$node"
                    conditions[key] = Alert(
                        key, NotifyEvent.NODE_MEMORY, Severity.WARNING,
                        prefix + s(R.string.att_node_mem, node, percent(recentMem.last())),
                        s(R.string.nt_sustained, n.sustainMinutes, config.memPercent),
                    )
                }
            }
        }

        // --- ZFS pools and SMART (need Sys.Audit; skipped quietly without it) ---
        for (node in online) {
            if (n.on(NotifyEvent.ZFS_DEGRADED)) {
                val pools = runCatching { client.get("/nodes/$node/disks/zfs") as? JsonArray }.getOrNull().orEmpty()
                pools.mapNotNull { it as? JsonObject }.forEach { p ->
                    val name = p.str("name").orEmpty()
                    val health = p.str("health").orEmpty()
                    if (name.isNotBlank() && health.isNotBlank() && !health.equals("ONLINE", ignoreCase = true)) {
                        val key = "${server.id}:zfs:$node:$name"
                        conditions[key] = Alert(key, NotifyEvent.ZFS_DEGRADED, Severity.CRITICAL, prefix + s(R.string.nt_zfs_title, name, health), s(R.string.nt_zfs_text, node))
                    }
                }
            }
            if (n.on(NotifyEvent.SMART_FAILED)) {
                val disks = runCatching { client.get("/nodes/$node/disks/list") as? JsonArray }.getOrNull().orEmpty()
                disks.mapNotNull { it as? JsonObject }.forEach { d ->
                    val health = d.str("health").orEmpty()
                    val dev = d.str("devpath").orEmpty()
                    if (health.equals("FAILED", ignoreCase = true)) {
                        val key = "${server.id}:smart:$node:$dev"
                        val model = d.str("model").orEmpty()
                        conditions[key] = Alert(key, NotifyEvent.SMART_FAILED, Severity.CRITICAL, prefix + s(R.string.nt_smart_title, dev), s(R.string.nt_smart_text, model, node))
                    }
                }
            }
        }

        // --- Tasks since the last check: failures, and guests stopped without a stop task ---
        val firstRun = prev.tasksSeen == 0L
        val since = if (firstRun) now else prev.tasksSeen
        val tasks = mutableListOf<TaskEntry>()
        var tasksOk = true
        for (node in online) {
            runCatching {
                client.get("/nodes/$node/tasks", ListSerializer(TaskEntry.serializer()), mapOf("since" to (since - 120).toString(), "limit" to "500", "source" to "all"))
            }.onSuccess { list -> tasks += list.map { it.copy(node = it.node.ifBlank { node }) } }
                .onFailure { tasksOk = false }
        }
        if (!firstRun) {
            val finished = tasks.filter { !it.isRunning && it.endtime > prev.tasksSeen }
            val failed = finished.filter { it.isError }
            if (n.on(NotifyEvent.BACKUP_FAILED) && !pushCovers) {
                finished.filter { it.type == "vzdump" && (it.isError || it.hasWarnings) }.forEach { t ->
                    val key = "${server.id}:backup:${t.upid}"
                    events += Alert(
                        key, NotifyEvent.BACKUP_FAILED, if (t.isError) Severity.CRITICAL else Severity.WARNING,
                        prefix + s(if (t.isError) R.string.att_backup_failed else R.string.att_backup_warnings, t.node),
                        t.status,
                    )
                }
            }
            if (n.on(NotifyEvent.TASK_FAILED)) {
                val other = failed.filter { it.type != "vzdump" }
                if (other.size == 1) {
                    val t = other.first()
                    events += Alert(
                        "${server.id}:task:${t.upid}", NotifyEvent.TASK_FAILED, Severity.WARNING,
                        prefix + s(R.string.nt_task_title, listOf(t.type, t.id).filter { it.isNotBlank() }.joinToString(" ")),
                        t.status,
                        vmid = t.id.toIntOrNull(),
                    )
                } else if (other.size > 1) {
                    events += Alert(
                        "${server.id}:tasks:$now", NotifyEvent.TASK_FAILED, Severity.WARNING,
                        prefix + context.resources.getQuantityString(R.plurals.nt_tasks_title, other.size, other.size),
                        other.take(5).joinToString("\n") { t -> "${t.type} ${t.id}: ${t.status}".trim() },
                    )
                }
            }
            if (n.on(NotifyEvent.GUEST_STOPPED) && tasksOk) {
                val running = guests.filter { it.isRunning }.map { it.vmid }.toSet()
                guests.filter { it.vmid in prev.running && it.isStopped && it.lock.isEmpty() && !it.isTemplate }.forEach { g ->
                    val explained = tasks.any { t -> t.id == g.vmid.toString() && t.type in STOP_TASKS } ||
                        tasks.any { t -> t.type in BULK_STOP_TASKS && t.node == g.node }
                    if (!explained && g.vmid !in running) {
                        events += Alert(
                            "${server.id}:stopped:${g.vmid}:$now", NotifyEvent.GUEST_STOPPED, Severity.WARNING,
                            prefix + s(R.string.nt_stopped_title, "${g.vmid} ${g.name}".trim()),
                            s(R.string.nt_stopped_text, g.node),
                            vmid = g.vmid,
                        )
                    }
                }
            }
        }
        val newestEnd = tasks.filter { !it.isRunning }.maxOfOrNull { it.endtime } ?: 0
        val tasksSeen = if (tasksOk) maxOf(now - 60, newestEnd, prev.tasksSeen) else prev.tasksSeen

        // --- Updates: at most one digest a day (Proxmox sends its own when push is on) ---
        var lastDigest = prev.lastDigestAt
        if (n.on(NotifyEvent.UPDATES) && !pushCovers && now - prev.lastDigestAt >= DIGEST_INTERVAL_S) {
            val counts = online.mapNotNull { node ->
                runCatching { OverviewParse.updateCount(client.get("/nodes/$node/apt/update")) }.getOrNull()?.let { node to it }
            }.filter { it.second > 0 }
            if (counts.isNotEmpty()) {
                lastDigest = now
                val total = counts.sumOf { it.second }
                events += Alert(
                    "${server.id}:updates", NotifyEvent.UPDATES, Severity.INFO,
                    prefix + context.resources.getQuantityString(R.plurals.nt_updates_title, total, total),
                    counts.joinToString(", ") { (node, c) -> "$node: $c" },
                )
            }
        }

        // Only new conditions are posted; a condition that got worse has a new (critical) key. Both
        // levels share one notification (the base key), so the critical one replaces the warning.
        val newConditions = conditions.filterKeys { it !in prev.active }
        val toPost = newConditions.values.groupBy { base(it.key) }
            .map { (b, list) -> list.minBy { it.severity.ordinal }.copy(key = b) } + events
        // A notification is removed once nothing of that condition is left (not when critical drops to warning).
        val currentBases = conditions.keys.map(::base).toSet()
        val gone = prev.active.filter { it.startsWith("${server.id}:") }.map(::base).filter { it !in currentBases }.toSet()

        return CheckResult(
            post = toPost,
            cancel = gone,
            state = CheckState(
                active = conditions.keys,
                running = guests.filter { it.isRunning }.map { it.vmid }.toSet(),
                tasksSeen = tasksSeen,
                unreachableCount = 0,
                lastDigestAt = lastDigest,
                lastCheckAt = now,
                lastResult = "ok",
            ),
        )
    }

    /** The server couldn't be read: notify after two failed checks in a row, so a short Wi-Fi drop doesn't. */
    private fun unreachable(server: Server, config: CheckConfig, prev: CheckState, e: PveError, now: Long): CheckResult {
        val count = prev.unreachableCount + 1
        val key = "${server.id}:unreachable"
        val post = mutableListOf<Alert>()
        val signIn = e is PveError.Unauthorized || e is PveError.NeedsSignIn || e is PveError.PinMismatch || e is PveError.UntrustedCertificate
        val notify = config.notify.on(NotifyEvent.NODE_UNREACHABLE) && key !in prev.active && (signIn || count >= 2)
        if (notify) {
            post += Alert(
                key, NotifyEvent.NODE_UNREACHABLE, if (signIn) Severity.WARNING else Severity.CRITICAL,
                s(if (signIn) R.string.nt_signin_title else R.string.nt_unreachable_title, server.name),
                s(if (signIn) R.string.nt_signin_text else R.string.nt_unreachable_text, server.host),
            )
        }
        // Keep the other conditions as they were: we don't know whether they changed.
        return CheckResult(
            post = post,
            cancel = emptySet(),
            state = prev.copy(
                active = if (notify || key in prev.active) prev.active + key else prev.active,
                unreachableCount = count,
                lastCheckAt = now,
                lastResult = e.javaClass.simpleName,
            ),
        )
    }

    private fun base(key: String) = key.removeSuffix(":w").removeSuffix(":c")

    /** A warning key, plus a critical key when critical, so getting worse notifies again but getting better doesn't. */
    private fun levelKeys(server: Server, what: String, sev: Severity): List<String> =
        if (sev == Severity.CRITICAL) listOf("${server.id}:$what:w", "${server.id}:$what:c") else listOf("${server.id}:$what:w")

    companion object {
        const val DIGEST_INTERVAL_S = 20 * 3600L

        /** Tasks that explain why a guest stopped. */
        val STOP_TASKS = setOf(
            "qmstop", "qmshutdown", "vzstop", "vzshutdown", "qmsuspend", "qmigrate", "vzmigrate",
            "qmdestroy", "vzdestroy", "qmrestore", "vzrestore", "qmrollback", "vzrollback",
            "hastop", "hamigrate", "harelocate", "vzdump", "qmclone", "vzclone", "qmtemplate", "vztemplate",
        )
        val BULK_STOP_TASKS = setOf("stopall", "suspendall", "migrateall")
    }
}
