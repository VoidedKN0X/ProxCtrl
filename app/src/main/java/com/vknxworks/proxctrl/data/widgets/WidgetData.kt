package com.vknxworks.proxctrl.data.widgets

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveJson
import com.vknxworks.proxctrl.data.guests.ClusterResource
import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.overview.Attention
import com.vknxworks.proxctrl.data.overview.AttentionInput
import com.vknxworks.proxctrl.data.overview.NodeEntry
import com.vknxworks.proxctrl.data.overview.NodeStatus
import com.vknxworks.proxctrl.data.overview.Severity
import com.vknxworks.proxctrl.data.overview.StorageEntry
import com.vknxworks.proxctrl.data.overview.Thresholds
import com.vknxworks.proxctrl.ui.widgets.HealthTileService
import com.vknxworks.proxctrl.ui.widgets.NodeStatusWidget
import com.vknxworks.proxctrl.ui.widgets.PinnedGuestsWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Node health as the widgets and the tile show it. */
@Serializable
enum class Health { OK, WARNING, CRITICAL, OFFLINE, NO_SERVER }

@Serializable
data class NodeSnapshot(
    val name: String,
    val online: Boolean,
    val cpu: Double = 0.0,
    val memUsed: Long = 0,
    val memTotal: Long = 0,
    val rootUsed: Long = 0,
    val rootTotal: Long = 0,
    val uptime: Long = 0,
) {
    val memFraction: Double? get() = if (memTotal > 0) memUsed.toDouble() / memTotal else null
    val rootFraction: Double? get() = if (rootTotal > 0) rootUsed.toDouble() / rootTotal else null
}

@Serializable
data class GuestSnapshot(val vmid: Int, val type: String, val name: String, val node: String, val status: String, val isTemplate: Boolean = false)

/**
 * What the widgets and the Quick Settings tile show: the active server's first node and its guests,
 * as last read. Kept on disk (no-backup storage) so they show the last known state while offline.
 * Names of servers, nodes and guests only; never tokens or tickets.
 */
@Serializable
data class WidgetSnapshot(
    val serverId: String? = null,
    val serverName: String = "",
    /** When the server was last read successfully (ms), 0 = never. */
    val updatedAt: Long = 0,
    /** When the last attempt was made (ms). */
    val checkedAt: Long = 0,
    /** The last attempt failed: what's shown is from [updatedAt]. */
    val failed: Boolean = false,
    val health: Health = Health.NO_SERVER,
    /** How many warnings or critical items the Overview would list. */
    val issues: Int = 0,
    val node: NodeSnapshot? = null,
    val guests: List<GuestSnapshot> = emptyList(),
) {
    /** Older than two refresh intervals, or the last refresh failed. */
    val isStale: Boolean get() = failed || (updatedAt > 0 && System.currentTimeMillis() - updatedAt > 35 * 60_000)
}

/** Disk storage for the snapshot and for which guests each pinned-guests widget shows. */
object WidgetStore {
    private val pinsSerializer = MapSerializer(String.serializer(), ListSerializer(Int.serializer()))
    private val lock = Mutex()

    private fun dir(context: Context) = File(context.noBackupFilesDir, "widgets").apply { mkdirs() }

    suspend fun read(context: Context): WidgetSnapshot = withContext(Dispatchers.IO) {
        runCatching { PveJson.decodeFromString(WidgetSnapshot.serializer(), File(dir(context), "snapshot.json").readText()) }.getOrNull() ?: WidgetSnapshot()
    }

    /** Synchronous read for the tile, which has to answer quickly on the main thread. */
    fun readBlocking(context: Context): WidgetSnapshot =
        runCatching { PveJson.decodeFromString(WidgetSnapshot.serializer(), File(dir(context), "snapshot.json").readText()) }.getOrNull() ?: WidgetSnapshot()

    suspend fun write(context: Context, snapshot: WidgetSnapshot) = withContext(Dispatchers.IO) {
        runCatching { File(dir(context), "snapshot.json").writeText(PveJson.encodeToString(WidgetSnapshot.serializer(), snapshot)) }
    }

    private suspend fun readPinsMap(context: Context): Map<String, List<Int>> = withContext(Dispatchers.IO) {
        runCatching { PveJson.decodeFromString(pinsSerializer, File(dir(context), "pins.json").readText()) }.getOrNull() ?: emptyMap()
    }

    /** The guests pinned on the widget with [appWidgetId], in the order picked. */
    suspend fun pins(context: Context, appWidgetId: Int): List<Int> = readPinsMap(context)[appWidgetId.toString()].orEmpty()

    suspend fun setPins(context: Context, appWidgetId: Int, vmids: List<Int>) = lock.withLock {
        val map = readPinsMap(context).toMutableMap()
        map[appWidgetId.toString()] = vmids
        withContext(Dispatchers.IO) { runCatching { File(dir(context), "pins.json").writeText(PveJson.encodeToString(pinsSerializer, map)) } }
    }

    suspend fun removePins(context: Context, appWidgetIds: IntArray) = lock.withLock {
        val map = readPinsMap(context).toMutableMap()
        appWidgetIds.forEach { map.remove(it.toString()) }
        withContext(Dispatchers.IO) { runCatching { File(dir(context), "pins.json").writeText(PveJson.encodeToString(pinsSerializer, map)) } }
    }
}

/** Reads the active server for the widgets and the tile, and redraws them. */
object WidgetRefresher {
    private val lock = Mutex()

    /** True when any ProxCtrl widget is on a home screen. */
    suspend fun anyWidgets(context: Context): Boolean {
        val m = GlanceAppWidgetManager(context)
        return runCatching {
            m.getGlanceIds(NodeStatusWidget::class.java).isNotEmpty() || m.getGlanceIds(PinnedGuestsWidget::class.java).isNotEmpty()
        }.getOrDefault(false)
    }

    /** Reads the server (never throws) and redraws widgets and the tile. */
    suspend fun refresh(context: Context) = lock.withLock {
        val app = context.applicationContext
        val c = app.container
        // App lock on with background checks off: no login to check with until ProxCtrl is unlocked.
        if (c.backgroundBlocked()) return@withLock
        val previous = WidgetStore.read(app)
        val active = c.sessions.active.value
        val now = System.currentTimeMillis()
        val next = if (active == null) {
            WidgetSnapshot(checkedAt = now)
        } else {
            try {
                val client = active.client
                val resources = client.get("/cluster/resources") as? JsonArray ?: JsonArray(emptyList())
                val objects = resources.mapNotNull { it as? JsonObject }
                val byType = objects.groupBy { (it["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
                val guests = byType["qemu"].orEmpty().plus(byType["lxc"].orEmpty())
                    .mapNotNull { runCatching { PveJson.decodeFromJsonElement(ClusterResource.serializer(), it) }.getOrNull() }
                    .mapNotNull { Guest.from(it) }
                    .sortedBy { it.vmid }
                val nodes = byType["node"].orEmpty().mapNotNull { runCatching { PveJson.decodeFromJsonElement(NodeEntry.serializer(), it) }.getOrNull() }
                    .sortedBy { it.node }
                val storages = byType["storage"].orEmpty().mapNotNull { runCatching { PveJson.decodeFromJsonElement(StorageEntry.serializer(), it) }.getOrNull() }
                val node = nodes.firstOrNull { it.isOnline } ?: nodes.firstOrNull()
                val status = node?.takeIf { it.isOnline }?.let { n -> runCatching { NodeStatus.parse(n.node, client.get("/nodes/${n.node}/status")) }.getOrNull() }
                val settings = c.settings.stored.first()
                val items = Attention.compute(
                    AttentionInput(
                        guests = guests,
                        nodes = nodes,
                        nodeStatus = status?.let { mapOf(it.node to it) }.orEmpty(),
                        storages = storages,
                        failedTasks = null,
                        lastBackupTask = emptyMap(),
                        newestBackup = null,
                        notBackedUp = null,
                        onBoot = emptyMap(),
                        subscription = emptyMap(),
                        updates = emptyMap(),
                        ha = emptyList(),
                    ),
                    Thresholds(settings.diskWarnPercent, settings.memWarnPercent, settings.backupMaxAgeDays),
                ).filter { it.severity != Severity.INFO }
                val health = when {
                    node == null || !node.isOnline -> Health.OFFLINE
                    items.any { it.severity == Severity.CRITICAL } -> Health.CRITICAL
                    items.isNotEmpty() -> Health.WARNING
                    else -> Health.OK
                }
                WidgetSnapshot(
                    serverId = active.server.id,
                    serverName = active.server.name,
                    updatedAt = now,
                    checkedAt = now,
                    failed = false,
                    health = health,
                    issues = items.size,
                    node = node?.let { n ->
                        NodeSnapshot(
                            name = n.node,
                            online = n.isOnline,
                            cpu = status?.cpu ?: n.cpu,
                            memUsed = status?.memUsed ?: n.mem.toLong(),
                            memTotal = status?.memTotal ?: n.maxmem.toLong(),
                            rootUsed = status?.rootUsed ?: n.disk.toLong(),
                            rootTotal = status?.rootTotal ?: n.maxdisk.toLong(),
                            uptime = status?.uptime ?: n.uptime.toLong(),
                        )
                    },
                    guests = guests.map { g -> GuestSnapshot(g.vmid, g.type.api, g.name, g.node, g.status, g.isTemplate) },
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Keep the last known state, marked as stale; unreachable counts as offline for the tile.
                if (previous.serverId == active.server.id) {
                    previous.copy(checkedAt = now, failed = true)
                } else {
                    WidgetSnapshot(serverId = active.server.id, serverName = active.server.name, checkedAt = now, failed = true, health = Health.OFFLINE)
                }
            }
        }
        WidgetStore.write(app, next)
        redraw(app)
    }

    /** Redraws widgets and the tile from the saved snapshot. */
    suspend fun redraw(context: Context) {
        runCatching { NodeStatusWidget().updateAll(context) }
        runCatching { PinnedGuestsWidget().updateAll(context) }
        runCatching { TileService.requestListeningState(context, ComponentName(context, HealthTileService::class.java)) }
    }

    private const val PERIODIC = "proxctrl-widgets"
    private const val ONCE = "proxctrl-widgets-now"
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Keeps a 15-minute refresh while any widget exists; stops it when the last one is removed. */
    suspend fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (anyWidgets(context)) {
            val request = PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            wm.cancelUniqueWork(PERIODIC)
        }
    }

    /** One refresh soon (app opened, a widget added, a guest started from a widget). */
    fun refreshSoon(context: Context, delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<WidgetWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
    }
}

/** Background refresh for the widgets and the tile. */
class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetRefresher.refresh(applicationContext)
        return Result.success()
    }
}
