package com.vknxworks.proxctrl.data.overview

import com.vknxworks.proxctrl.data.guests.Guest

enum class Severity { CRITICAL, WARNING, INFO }

/**
 * Something on the server that needs a look. Built by [Attention.compute] from plain data, so the
 * same rules drive the Overview list and (later) notifications. Text is chosen by the UI.
 */
sealed interface AttentionItem {
    val severity: Severity

    /** Stable identity, e.g. for notifications not to repeat themselves. */
    val key: String

    data class GuestDisk(val guest: Guest, val fraction: Double, override val severity: Severity) : AttentionItem {
        override val key get() = "guest-disk-${guest.vmid}"
    }

    data class NodeMemory(val node: String, val fraction: Double, override val severity: Severity) : AttentionItem {
        override val key get() = "node-mem-$node"
    }

    data class NodeRootDisk(val node: String, val fraction: Double, override val severity: Severity) : AttentionItem {
        override val key get() = "node-root-$node"
    }

    data class NodeOffline(val node: String) : AttentionItem {
        override val severity get() = Severity.CRITICAL
        override val key get() = "node-offline-$node"
    }

    data class StoppedOnBoot(val guest: Guest) : AttentionItem {
        override val severity get() = Severity.WARNING
        override val key get() = "onboot-${guest.vmid}"
    }

    data class FailedTasks(val tasks: List<TaskEntry>) : AttentionItem {
        override val severity get() = Severity.WARNING
        override val key get() = "failed-tasks"
    }

    data class BackupFailed(val task: TaskEntry) : AttentionItem {
        override val severity get() = if (task.isError) Severity.CRITICAL else Severity.WARNING
        override val key get() = "backup-failed-${task.node}"
    }

    /** Guests in a backup job whose newest backup is older than the limit (or that have none). */
    data class BackupOld(val guests: List<Guest>, val maxAgeDays: Int) : AttentionItem {
        override val severity get() = Severity.WARNING
        override val key get() = "backup-old"
    }

    /** Guests that no backup job includes. */
    data class NotBackedUp(val guests: List<Guest>) : AttentionItem {
        override val severity get() = Severity.INFO
        override val key get() = "not-backed-up"
    }

    data class StorageFull(val storage: StorageEntry, val fraction: Double, override val severity: Severity) : AttentionItem {
        override val key get() = "storage-${storage.node}-${storage.storage}"
    }

    data class StorageUnavailable(val storage: StorageEntry) : AttentionItem {
        override val severity get() = Severity.WARNING
        override val key get() = "storage-down-${storage.node}-${storage.storage}"
    }

    /** A subscription key exists but isn't active (expired, invalid, suspended). */
    data class Subscription(val node: String, val status: String) : AttentionItem {
        override val severity get() = Severity.WARNING
        override val key get() = "subscription-$node"
    }

    data class Updates(val node: String, val count: Int) : AttentionItem {
        override val severity get() = Severity.INFO
        override val key get() = "updates-$node"
    }

    data class HaProblem(val entry: HaEntry) : AttentionItem {
        override val severity get() = Severity.CRITICAL
        override val key get() = "ha-${entry.id}"
    }
}

/** Everything the attention rules look at. Missing data (null) means "unknown": no item is made from it. */
data class AttentionInput(
    val guests: List<Guest>,
    val nodes: List<NodeEntry>,
    val nodeStatus: Map<String, NodeStatus>,
    val storages: List<StorageEntry>,
    /** Failed (error) tasks of the last 24 hours, newest first. */
    val failedTasks: List<TaskEntry>?,
    /** The newest backup (vzdump) task per node. */
    val lastBackupTask: Map<String, TaskEntry>,
    /** Newest backup time per VMID; null when backups couldn't be read. */
    val newestBackup: Map<Int, Long>?,
    /** VMIDs no backup job includes; null when unknown. */
    val notBackedUp: Set<Int>?,
    /** Start-at-boot setting of stopped guests, by VMID. */
    val onBoot: Map<Int, Boolean>,
    val subscription: Map<String, String>,
    val updates: Map<String, Int>,
    val ha: List<HaEntry>,
)

data class Thresholds(val diskPercent: Int, val memPercent: Int, val backupMaxAgeDays: Int)

object Attention {

    /** Critical from 95 %, or 10 points above the warning level when that's lower. */
    fun severityFor(fraction: Double, warnPercent: Int): Severity? {
        val pct = fraction * 100
        return when {
            pct >= minOf(95, warnPercent + 10) -> Severity.CRITICAL
            pct >= warnPercent -> Severity.WARNING
            else -> null
        }
    }

    fun compute(input: AttentionInput, t: Thresholds, nowSeconds: Long = System.currentTimeMillis() / 1000): List<AttentionItem> {
        val items = mutableListOf<AttentionItem>()

        input.nodes.filter { !it.isOnline }.forEach { items += AttentionItem.NodeOffline(it.node) }

        input.nodeStatus.values.forEach { s ->
            s.memFraction?.let { f -> severityFor(f, t.memPercent)?.let { items += AttentionItem.NodeMemory(s.node, f, it) } }
            s.rootFraction?.let { f -> severityFor(f, t.diskPercent)?.let { items += AttentionItem.NodeRootDisk(s.node, f, it) } }
        }

        val realGuests = input.guests.filter { !it.isTemplate }
        realGuests.forEach { g ->
            g.diskFraction?.let { f -> severityFor(f, t.diskPercent)?.let { items += AttentionItem.GuestDisk(g, f, it) } }
            if (g.isStopped && input.onBoot[g.vmid] == true) items += AttentionItem.StoppedOnBoot(g)
        }

        input.storages.forEach { s ->
            if (!s.isAvailable && s.status.isNotBlank()) {
                items += AttentionItem.StorageUnavailable(s)
            } else {
                s.fraction?.let { f -> severityFor(f, t.diskPercent)?.let { items += AttentionItem.StorageFull(s, f, it) } }
            }
        }

        input.failedTasks?.filter { it.type != "vzdump" }?.takeIf { it.isNotEmpty() }?.let { items += AttentionItem.FailedTasks(it) }

        input.lastBackupTask.values.forEach { task ->
            if (task.isError || task.hasWarnings) items += AttentionItem.BackupFailed(task)
        }

        val notBackedUp = input.notBackedUp
        input.newestBackup?.let { newest ->
            val limit = nowSeconds - t.backupMaxAgeDays * 86_400L
            // Only guests a backup job includes; without job info, only guests that have had a backup before.
            val old = realGuests.filter { g ->
                val inJob = notBackedUp?.let { g.vmid !in it } ?: newest.containsKey(g.vmid)
                inJob && (newest[g.vmid] ?: 0) < limit
            }
            if (old.isNotEmpty()) items += AttentionItem.BackupOld(old.sortedBy { it.vmid }, t.backupMaxAgeDays)
        }
        notBackedUp?.let { ids ->
            val missing = realGuests.filter { it.vmid in ids }
            if (missing.isNotEmpty()) items += AttentionItem.NotBackedUp(missing.sortedBy { it.vmid })
        }

        input.subscription.forEach { (node, status) ->
            if (status in setOf("expired", "invalid", "suspended")) items += AttentionItem.Subscription(node, status)
        }
        input.updates.forEach { (node, count) -> if (count > 0) items += AttentionItem.Updates(node, count) }

        input.ha.forEach { e ->
            val bad = (e.type == "service" && e.state in setOf("error", "fence", "recovery")) ||
                (e.type == "quorum" && e.status.isNotBlank() && !e.status.equals("OK", ignoreCase = true))
            if (bad) items += AttentionItem.HaProblem(e)
        }

        return items.sortedWith(compareBy<AttentionItem> { it.severity.ordinal }.thenBy { it.key })
    }
}
