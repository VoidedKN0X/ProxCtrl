package com.vknxworks.proxctrl.data.overview

import android.content.Context
import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.api.PveJson
import com.vknxworks.proxctrl.data.guests.GuestApi
import com.vknxworks.proxctrl.data.guests.GuestConfig
import com.vknxworks.proxctrl.data.guests.GuestRepository
import com.vknxworks.proxctrl.data.guests.RrdPoint
import com.vknxworks.proxctrl.data.server.ActiveServer
import com.vknxworks.proxctrl.data.server.Sessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** What the Overview shows for the active server. */
data class OverviewState(
    val serverId: String? = null,
    val nodes: List<NodeEntry> = emptyList(),
    val nodeStatus: Map<String, NodeStatus> = emptyMap(),
    /** Last hour of node graphs, per node. */
    val rrd: Map<String, List<RrdPoint>> = emptyMap(),
    val storages: List<StorageEntry> = emptyList(),
    val failedTasks: List<TaskEntry>? = null,
    val lastBackupTask: Map<String, TaskEntry> = emptyMap(),
    val newestBackup: Map<Int, Long>? = null,
    val notBackedUp: Set<Int>? = null,
    val onBoot: Map<Int, Boolean> = emptyMap(),
    val subscription: Map<String, String> = emptyMap(),
    val updates: Map<String, Int> = emptyMap(),
    val ha: List<HaEntry> = emptyList(),
    val updatedAt: Long = 0,
    val error: PveError? = null,
) {
    val isClustered: Boolean get() = nodes.size > 1
}

@Serializable
private data class OverviewCache(
    val updatedAt: Long,
    val nodes: List<NodeEntry>,
    val nodeStatus: List<NodeStatus>,
    val storages: List<StorageEntry>,
)

/**
 * Collects everything the Overview and its attention list need. Each source has its own pace, so
 * a refresh every few seconds stays cheap: node status every call, storage and tasks every
 * minute, guest boot settings every 5 minutes, backups every 10, subscription and updates every 30.
 * Anything the sign-in isn't allowed to read is skipped quietly.
 */
class OverviewRepository(
    private val context: Context,
    private val sessions: Sessions,
    private val guests: GuestRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(OverviewState())
    val state: StateFlow<OverviewState> = _state.asStateFlow()

    private val lock = Mutex()
    private val lastRun = HashMap<String, Long>()
    private val onBootCheckedAt = HashMap<Int, Long>()

    init {
        scope.launch {
            sessions.active.collect { active -> switchTo(active) }
        }
    }

    private suspend fun switchTo(active: ActiveServer?) = lock.withLock {
        lastRun.clear()
        onBootCheckedAt.clear()
        if (active == null) {
            _state.value = OverviewState()
            return@withLock
        }
        if (_state.value.serverId == active.server.id) return@withLock
        val cached = readCache(active.server.id)
        _state.value = OverviewState(
            serverId = active.server.id,
            nodes = cached?.nodes.orEmpty(),
            nodeStatus = cached?.nodeStatus.orEmpty().associateBy { it.node },
            storages = cached?.storages.orEmpty(),
            updatedAt = cached?.updatedAt ?: 0,
        )
    }

    private fun due(key: String, intervalMs: Long, force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - (lastRun[key] ?: 0) < intervalMs) return false
        lastRun[key] = now
        return true
    }

    /** Refreshes the Overview. [force] (pull to refresh) reloads every source now. */
    suspend fun refresh(force: Boolean = false) {
        val active = sessions.active.value ?: return
        val client = active.client
        lock.withLock {
            if (_state.value.serverId != active.server.id) return
            try {
                // The guest list feeds disk and boot warnings.
                guests.refresh()

                val nodes = client.get("/nodes", ListSerializer(NodeEntry.serializer())).sortedBy { it.node }
                val online = nodes.filter { it.isOnline }.map { it.node }
                val statuses = coroutineScope {
                    online.map { n -> async { runCatching { NodeStatus.parse(n, client.get("/nodes/$n/status")) }.getOrNull() } }
                        .awaitAll().filterNotNull().associateBy { it.node }
                }
                var s = _state.value.copy(nodes = nodes, nodeStatus = statuses, updatedAt = System.currentTimeMillis(), error = null)
                _state.value = s

                if (due("rrd", 60_000, force)) {
                    val rrd = coroutineScope {
                        online.map { n ->
                            async { n to runCatching { GuestApi.parseRrd(client.get("/nodes/$n/rrddata", mapOf("timeframe" to "hour", "cf" to "AVERAGE"))) }.getOrNull() }
                        }.awaitAll()
                    }.mapNotNull { (n, r) -> r?.let { n to it } }.toMap()
                    s = s.copy(rrd = s.rrd + rrd)
                }

                if (due("storage", 60_000, force)) {
                    runCatching { client.get("/cluster/resources", ListSerializer(StorageEntry.serializer()), mapOf("type" to "storage")) }
                        .onSuccess { list -> s = s.copy(storages = list.sortedWith(compareBy({ it.node }, { it.storage }))) }
                }

                if (due("tasks", 60_000, force)) s = loadTasks(client, online, s)
                if (due("onboot", 60_000, force)) s = loadOnBoot(client, s)
                if (due("backups", 10 * 60_000, force)) s = loadBackups(client, s)
                if (due("node-info", 30 * 60_000, force)) s = loadNodeInfo(client, online, s)

                _state.value = s
                writeCache(active.server.id, OverviewCache(s.updatedAt, s.nodes, s.nodeStatus.values.toList(), s.storages))
            } catch (e: PveError) {
                _state.value = _state.value.copy(error = e)
            }
        }
    }

    private suspend fun loadTasks(client: PveClient, online: List<String>, s: OverviewState): OverviewState {
        val since = (System.currentTimeMillis() / 1000 - 86_400).toString()
        var failedOk = false
        val failed = mutableListOf<TaskEntry>()
        val lastBackup = HashMap<String, TaskEntry>()
        for (n in online) {
            runCatching {
                client.get("/nodes/$n/tasks", ListSerializer(TaskEntry.serializer()), mapOf("errors" to "1", "since" to since, "limit" to "50"))
            }.onSuccess { failed += it.map { t -> t.copy(node = t.node.ifBlank { n }) }; failedOk = true }
            runCatching {
                client.get("/nodes/$n/tasks", ListSerializer(TaskEntry.serializer()), mapOf("typefilter" to "vzdump", "limit" to "1"))
            }.getOrNull()?.firstOrNull()?.let { lastBackup[n] = it.copy(node = it.node.ifBlank { n }) }
        }
        return s.copy(
            failedTasks = if (failedOk) failed.filter { it.isError }.sortedByDescending { it.starttime } else s.failedTasks,
            lastBackupTask = lastBackup,
        )
    }

    /** Start-at-boot for stopped guests: one config read per stopped guest, at most every 5 minutes. */
    private suspend fun loadOnBoot(client: PveClient, s: OverviewState): OverviewState {
        val now = System.currentTimeMillis()
        val stopped = guests.state.value.guests.filter { it.isStopped && !it.isTemplate }
        val stale = stopped.filter { now - (onBootCheckedAt[it.vmid] ?: 0) > 5 * 60_000 }
        if (stale.isEmpty()) return s.copy(onBoot = s.onBoot.filterKeys { id -> stopped.any { it.vmid == id } })
        val results = coroutineScope {
            stale.map { g ->
                async {
                    runCatching { GuestConfig.parse(client.get("/nodes/${g.node}/${g.type.api}/${g.vmid}/config")).onBoot }
                        .getOrNull()?.let { g.vmid to it }
                }
            }.awaitAll().filterNotNull()
        }
        results.forEach { (id, _) -> onBootCheckedAt[id] = now }
        val merged = (s.onBoot + results).filterKeys { id -> stopped.any { it.vmid == id } }
        return s.copy(onBoot = merged)
    }

    /** Newest backup per guest across backup storages, and guests no job includes. */
    private suspend fun loadBackups(client: PveClient, s: OverviewState): OverviewState {
        // A shared storage is listed once per node; read it once.
        val targets = s.storages.filter { it.holdsBackups && it.isAvailable }.distinctBy { if (it.shared != 0) it.storage else "${it.node}/${it.storage}" }
        var anyOk = false
        val newest = HashMap<Int, Long>()
        for (st in targets) {
            runCatching { OverviewParse.backups(client.get("/nodes/${st.node}/storage/${st.storage}/content", mapOf("content" to "backup"))) }
                .onSuccess { files ->
                    anyOk = true
                    files.forEach { f -> if (f.ctime > (newest[f.vmid] ?: 0)) newest[f.vmid] = f.ctime }
                }
        }
        val notBackedUp = runCatching { OverviewParse.notBackedUp(client.get("/cluster/backup-info/not-backed-up")) }.getOrNull()
        return s.copy(newestBackup = if (anyOk) newest else s.newestBackup, notBackedUp = notBackedUp ?: s.notBackedUp)
    }

    /** Subscription, available updates (needs Sys.Modify on the node) and HA state (clusters). */
    private suspend fun loadNodeInfo(client: PveClient, online: List<String>, s: OverviewState): OverviewState {
        val perms = sessions.access.value.permissions
        val subscription = HashMap<String, String>()
        val updates = HashMap<String, Int>()
        for (n in online) {
            runCatching { OverviewParse.subscriptionStatus(client.get("/nodes/$n/subscription")) }.getOrNull()?.let { subscription[n] = it }
            if (perms.has("/nodes/$n", "Sys.Modify")) {
                runCatching { OverviewParse.updateCount(client.get("/nodes/$n/apt/update")) }.getOrNull()?.let { updates[n] = it }
            }
        }
        val ha = if (s.isClustered) runCatching { OverviewParse.ha(client.get("/cluster/ha/status/current")) }.getOrNull().orEmpty() else emptyList()
        return s.copy(subscription = subscription, updates = updates, ha = ha)
    }

    fun attentionInput(): AttentionInput {
        val s = _state.value
        return AttentionInput(
            guests = guests.state.value.guests,
            nodes = s.nodes,
            nodeStatus = s.nodeStatus,
            storages = s.storages,
            failedTasks = s.failedTasks,
            lastBackupTask = s.lastBackupTask,
            newestBackup = s.newestBackup,
            notBackedUp = s.notBackedUp,
            onBoot = s.onBoot,
            subscription = s.subscription,
            updates = s.updates,
            ha = s.ha,
        )
    }

    private fun cacheFile(serverId: String) = File(File(context.noBackupFilesDir, "cache").apply { mkdirs() }, "overview-$serverId.json")

    private suspend fun readCache(serverId: String): OverviewCache? = withContext(Dispatchers.IO) {
        runCatching { PveJson.decodeFromString(OverviewCache.serializer(), cacheFile(serverId).readText()) }.getOrNull()
    }

    private suspend fun writeCache(serverId: String, cache: OverviewCache) = withContext(Dispatchers.IO) {
        runCatching { cacheFile(serverId).writeText(PveJson.encodeToString(OverviewCache.serializer(), cache)) }
    }

    fun forget(serverId: String) {
        runCatching { cacheFile(serverId).delete() }
    }
}
