package com.vknxworks.proxctrl.data.guests

import android.content.Context
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.api.PveJson
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File

/** The guest list of the active server, and how fresh it is. */
data class GuestsState(
    val serverId: String? = null,
    val guests: List<Guest> = emptyList(),
    /** When [guests] was last read from the server (ms); 0 = never. */
    val updatedAt: Long = 0,
    /** The last refresh failed; [guests] is the last known state. */
    val error: PveError? = null,
    val loading: Boolean = false,
    val tagColors: TagColors = TagColors.DEFAULT,
) {
    val isStale: Boolean get() = error != null && updatedAt > 0
}

@Serializable
private data class GuestCache(val updatedAt: Long, val guests: List<Guest>)

/**
 * Loads all guests with one call (GET /cluster/resources?type=vm), never one call per guest.
 * The last good list is kept on disk per server, so the app shows it straight away (marked stale)
 * when the server can't be reached. VM disk use comes from the guest agent on a slower cycle.
 */
class GuestRepository(
    private val context: Context,
    private val sessions: Sessions,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(GuestsState())
    val state: StateFlow<GuestsState> = _state.asStateFlow()

    private val refreshLock = Mutex()
    private val agentDisks = java.util.concurrent.ConcurrentHashMap<Int, AgentDisk>()
    /** When the agent of a VM last failed (not installed, not running): don't ask again for a while. */
    private val agentFailedAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private var agentCheckedAt = 0L
    private var tagColorsLoadedFor: String? = null

    init {
        scope.launch {
            sessions.active.collect { active -> switchTo(active) }
        }
    }

    private suspend fun switchTo(active: ActiveServer?) {
        refreshLock.withLock {
            agentDisks.clear()
            agentFailedAt.clear()
            agentCheckedAt = 0
            tagColorsLoadedFor = null
            if (active == null) {
                _state.value = GuestsState()
                return
            }
            if (_state.value.serverId == active.server.id) return
            val cached = readCache(active.server.id)
            _state.value = GuestsState(
                serverId = active.server.id,
                guests = cached?.guests.orEmpty(),
                updatedAt = cached?.updatedAt ?: 0,
            )
        }
    }

    /** Reads the guest list now. Errors are kept in the state; the last known list stays visible. */
    suspend fun refresh() {
        val active = sessions.active.value ?: return
        refreshLock.withLock {
            if (_state.value.serverId != active.server.id) return
            _state.value = _state.value.copy(loading = true)
            try {
                val resources = active.client.get("/cluster/resources", ListSerializer(ClusterResource.serializer()), mapOf("type" to "vm"))
                val guests = resources.mapNotNull(Guest::from).sortedBy { it.vmid }
                if (tagColorsLoadedFor != active.server.id) {
                    tagColorsLoadedFor = active.server.id
                    // Needs Sys.Audit on /; without it tags keep their derived colours.
                    val colors = runCatching { TagColors.fromClusterOptions(active.client.get("/cluster/options")) }.getOrNull()
                    if (colors != null) _state.value = _state.value.copy(tagColors = colors)
                }
                refreshAgentDisks(active, guests)
                val now = System.currentTimeMillis()
                val withDisks = guests.map(::withAgentDisk)
                _state.value = _state.value.copy(guests = withDisks, updatedAt = now, error = null, loading = false)
                writeCache(active.server.id, GuestCache(now, withDisks))
            } catch (e: PveError) {
                _state.value = _state.value.copy(error = e, loading = false)
            }
        }
    }

    private fun withAgentDisk(g: Guest): Guest {
        if (g.type != GuestType.QEMU) return g
        val d = agentDisks[g.vmid] ?: return g
        return g.copy(disk = d.used, maxDisk = d.total)
    }

    /**
     * Disk use for running VMs from the QEMU guest agent (get-fsinfo), at most once a minute.
     * VMs whose agent doesn't answer are skipped for ten minutes.
     */
    private suspend fun refreshAgentDisks(active: ActiveServer, guests: List<Guest>) {
        val now = System.currentTimeMillis()
        if (now - agentCheckedAt < AGENT_INTERVAL_MS) return
        agentCheckedAt = now
        val agentPriv = if ((sessions.access.value.version?.major ?: 0) in 1..8) "VM.Monitor" else "VM.GuestAgent.Audit"
        val perms = sessions.access.value.permissions
        val vms = guests.filter {
            it.type == GuestType.QEMU && it.isRunning && !it.isTemplate && perms.has(it.aclPath, agentPriv) &&
                now - (agentFailedAt[it.vmid] ?: 0) > AGENT_RETRY_MS
        }
        guests.filter { it.type == GuestType.QEMU && !it.isRunning }.forEach { agentDisks.remove(it.vmid) }
        coroutineScope {
            vms.map { vm ->
                async {
                    try {
                        val data = active.client.get("/nodes/${vm.node}/qemu/${vm.vmid}/agent/get-fsinfo")
                        val disk = parseFsInfo(data)
                        if (disk != null) agentDisks[vm.vmid] = disk else agentFailedAt[vm.vmid] = now
                    } catch (e: PveError) {
                        agentFailedAt[vm.vmid] = now
                    }
                }
            }.awaitAll()
        }
    }

    private fun cacheFile(serverId: String) = File(File(context.noBackupFilesDir, "cache").apply { mkdirs() }, "guests-$serverId.json")

    private suspend fun readCache(serverId: String): GuestCache? = withContext(Dispatchers.IO) {
        runCatching { PveJson.decodeFromString(GuestCache.serializer(), cacheFile(serverId).readText()) }.getOrNull()
    }

    private suspend fun writeCache(serverId: String, cache: GuestCache) = withContext(Dispatchers.IO) {
        runCatching { cacheFile(serverId).writeText(PveJson.encodeToString(GuestCache.serializer(), cache)) }
    }

    /** Forgets the cached list of a removed server. */
    fun forget(serverId: String) {
        runCatching { cacheFile(serverId).delete() }
    }

    companion object {
        const val AGENT_INTERVAL_MS = 60_000L
        const val AGENT_RETRY_MS = 10 * 60_000L

        /**
         * Sums the guest's real filesystems from get-fsinfo ({"result": [{mountpoint, type, used-bytes,
         * total-bytes}, …]}), skipping pseudo filesystems and counting each mount point once.
         * Returns null when the agent reports nothing usable.
         */
        fun parseFsInfo(data: kotlinx.serialization.json.JsonElement?): AgentDisk? {
            val list = ((data as? JsonObject)?.get("result") as? JsonArray) ?: return null
            var used = 0L
            var total = 0L
            val seenMounts = HashSet<String>()
            val seenDevices = HashSet<String>()
            for (item in list) {
                val o = item as? JsonObject ?: continue
                val fsType = (o["type"] as? JsonPrimitive)?.content.orEmpty().lowercase()
                if (fsType in PSEUDO_FS) continue
                val mount = (o["mountpoint"] as? JsonPrimitive)?.content.orEmpty()
                if (!seenMounts.add(mount)) continue
                // The same filesystem mounted twice (bind mounts, snap) shares the device name.
                val device = (o["name"] as? JsonPrimitive)?.content.orEmpty()
                if (device.isNotEmpty() && !seenDevices.add(device)) continue
                val u = (o["used-bytes"] as? JsonPrimitive)?.doubleOrNull ?: continue
                val t = (o["total-bytes"] as? JsonPrimitive)?.doubleOrNull ?: continue
                if (t <= 0) continue
                used += u.toLong()
                total += t.toLong()
            }
            return if (total > 0) AgentDisk(used, total) else null
        }

        private val PSEUDO_FS = setOf(
            "tmpfs", "devtmpfs", "squashfs", "overlay", "proc", "sysfs", "cgroup", "cgroup2", "autofs",
            "efivarfs", "vfat", "iso9660", "udf", "fuse.snapfuse", "ramfs", "nsfs", "tracefs",
        )
    }
}
