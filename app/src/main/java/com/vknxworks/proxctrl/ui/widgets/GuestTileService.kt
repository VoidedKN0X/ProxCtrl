package com.vknxworks.proxctrl.ui.widgets

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vknxworks.proxctrl.MainActivity
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.guests.PowerAction
import com.vknxworks.proxctrl.data.guests.power
import com.vknxworks.proxctrl.data.server.Server
import com.vknxworks.proxctrl.data.tasks.TaskApi
import com.vknxworks.proxctrl.data.widgets.GuestTileLogic
import com.vknxworks.proxctrl.data.widgets.GuestTileTarget
import com.vknxworks.proxctrl.data.widgets.WidgetRefresher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Quick Settings tile for one guest (VM or container) picked under Settings › Widgets and Quick
 * Settings, on the server it was picked from (remembered by ID, whichever server is active in the app).
 *
 * - Lit while the guest runs, dimmed while it's stopped; the subtitle says which, and what a tap does.
 * - While the panel is open it follows the guest live: it collects the app's guest list (when that
 *   server is the active one) and its own state, asks the server every [REFRESH_MS] (every
 *   [FAST_MS] right after a start or shut-down), and follows the task Proxmox started.
 *   (requestListeningState alone doesn't redraw a tile that is already listening.)
 * - Tap: starts a stopped guest, or asks in a dialog before shutting a running one down gracefully.
 *   Every action needs the phone unlocked first (unlockAndRun).
 * - No guest picked, the server removed, a read-only server, a paused or locked guest: a tap opens ProxCtrl.
 * - App lock: with logins sealed by the app PIN (background checks off) the tile only says ProxCtrl is
 *   locked. With "Confirm changes" on, a tap opens ProxCtrl's own confirmation, since the tile can't ask
 *   for the fingerprint or PIN itself.
 */
class GuestTileService : TileService() {

    /** A failure (storage, an odd answer) must never take System UI's tile down: it only ends that job. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, _ -> })
    private var listening: Job? = null

    /** What's known about the tile's guest. */
    private sealed interface View {
        data object Loading : View
        data object NoTarget : View
        data object NoServer : View
        data object Locked : View
        data object Missing : View
        data class Known(val guest: Guest, val stale: Boolean = false) : View
        data class Failed(val error: PveError) : View
    }

    private val view = MutableStateFlow<View>(View.Loading)

    /** Replaces the subtitle for a while (starting, shutting down, failed); null shows the state. */
    private val note = MutableStateFlow<String?>(null)

    /** Ask more often until then (ms), after a start or shut-down. */
    @Volatile
    private var fastUntil = 0L
    private var busy = false

    /** A client for the tile's server, kept while its saved settings stay the same. */
    private var cached: Pair<Server, PveClient>? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch { listen() }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun listen() = coroutineScope {
        val c = container
        launch {
            combine(view, note, c.guestTile.target, c.sessions.servers) { _, _, _, _ -> }.collect { render() }
        }
        // The app's own guest list, when the tile's server is the active one: changes made in ProxCtrl show at once.
        launch {
            c.guests.state.collect { list ->
                val t = c.guestTile.target.value ?: return@collect
                if (c.sessions.active.value?.server?.id != t.serverId) return@collect
                list.guests.firstOrNull { it.vmid == t.vmid && it.type == t.guestType }?.let { view.value = View.Known(it) }
            }
        }
        while (isActive) {
            refresh()
            delay(if (System.currentTimeMillis() < fastUntil) FAST_MS else REFRESH_MS)
        }
    }

    /** The tile's server, if it's still saved. */
    private fun serverOf(t: GuestTileTarget): Server? = container.sessions.servers.value.firstOrNull { it.id == t.serverId }

    /** A client for [server]; reading its login touches storage and the Keystore, so off the main thread. */
    private suspend fun clientFor(server: Server): PveClient {
        cached?.let { (s, cl) -> if (s == server) return cl }
        val cl = withContext(Dispatchers.IO) { container.sessions.clientFor(server) }
        cached = server to cl
        return cl
    }

    private suspend fun refresh() {
        val c = container
        val t = c.guestTile.target.value ?: run { view.value = View.NoTarget; return }
        val server = serverOf(t) ?: run { view.value = View.NoServer; return }
        if (c.backgroundBlocked()) {
            cached = null
            view.value = View.Locked
            return
        }
        try {
            val data = clientFor(server).get(GuestTileLogic.RESOURCES_PATH, GuestTileLogic.RESOURCES_QUERY)
            view.value = GuestTileLogic.find(data, t)?.let { View.Known(it) } ?: View.Missing
        } catch (e: PveError) {
            // Keep showing the last known state, marked as old, rather than nothing.
            val last = view.value
            view.value = if (last is View.Known) last.copy(stale = true) else View.Failed(e)
        }
    }

    override fun onClick() {
        super.onClick()
        val c = container
        val t = c.guestTile.target.value
        val server = t?.let(::serverOf)
        if (t == null || server == null) {
            // Nothing picked (or its server was removed): open the setting where the guest is picked.
            openApp(WidgetActions.ACTION_OPEN_TILE_SETTINGS)
            return
        }
        if (c.backgroundBlocked() || server.readOnly) {
            openApp(null)
            return
        }
        val guest = (view.value as? View.Known)?.guest
        if (guest == null) {
            scope.launch { refresh() }
            return
        }
        val action = GuestTileLogic.tapAction(guest)
        if (action == null) {
            openApp(null)
            return
        }
        if (c.vault.enabled && c.settings.settings.value.lockChanges) {
            // "Confirm changes": ProxCtrl asks (and then wants the fingerprint or PIN), not the tile.
            openApp(WidgetActions.ACTION_CONFIRM_POWER) {
                putExtra(WidgetActions.EXTRA_SERVER, server.id)
                putExtra(WidgetActions.EXTRA_VMID, guest.vmid)
                putExtra(WidgetActions.EXTRA_ACTION, if (action == PowerAction.START) WidgetActions.START else WidgetActions.SHUTDOWN)
                putExtra(WidgetActions.EXTRA_INTERNAL, WidgetActions.internalToken(this@GuestTileService))
            }
            return
        }
        val run = Runnable {
            if (action == PowerAction.START) power(server, guest, action) else confirmShutdown(server, guest)
        }
        // Never act from the lock screen: the phone is unlocked first, for start and shut-down alike.
        if (isLocked) unlockAndRun(run) else run.run()
    }

    private fun guestLabel(g: Guest) = getString(R.string.guest_tile_guest, g.vmid, g.name.ifBlank { g.vmid.toString() })

    /** The graceful shut-down asks first, in a dialog over the panel. */
    private fun confirmShutdown(server: Server, guest: Guest) {
        val dialog = AlertDialog.Builder(this, R.style.Theme_ProxCtrl_TileDialog)
            .setTitle(getString(R.string.widget_confirm_shutdown_title, guestLabel(guest)))
            .setMessage(getString(R.string.guest_tile_shutdown_body, server.name))
            .setPositiveButton(R.string.widget_shutdown) { _, _ -> power(server, guest, PowerAction.SHUTDOWN) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        showDialog(dialog)
    }

    private fun power(server: Server, guest: Guest, action: PowerAction) {
        if (busy) return
        busy = true
        note.value = getString(if (action == PowerAction.START) R.string.guest_tile_starting else R.string.guest_tile_shutting_down)
        scope.launch {
            try {
                val client = clientFor(server)
                val result = client.power(guest, action)
                val error = result.error
                if (error != null) {
                    showBriefly(failure(action, error))
                    return@launch
                }
                fastUntil = System.currentTimeMillis() + FAST_WINDOW_MS
                refresh()
                // Follow the task: a failed start or shut-down (for example a guest that ignores ACPI) is said on the tile.
                val upid = result.upid
                val state = if (upid != null) withTimeoutOrNull(TASK_WAIT_MS) { runCatching { TaskApi.await(client, upid) }.getOrNull() } else null
                refresh()
                if (state != null && !state.ok) {
                    showBriefly(getString(if (action == PowerAction.START) R.string.guest_tile_start_failed else R.string.guest_tile_shutdown_failed))
                } else {
                    note.value = null
                }
                // The widgets show the same guests.
                WidgetRefresher.refreshSoon(applicationContext, delaySeconds = 5)
            } finally {
                busy = false
            }
        }
    }

    private fun failure(action: PowerAction, e: PveError): String = when (e) {
        is PveError.Forbidden -> getString(R.string.guest_tile_not_allowed)
        is PveError.Unreachable -> getString(R.string.guest_tile_unreachable)
        else -> getString(if (action == PowerAction.START) R.string.guest_tile_start_failed else R.string.guest_tile_shutdown_failed)
    }

    private suspend fun showBriefly(text: String) {
        note.value = text
        delay(4_000)
        if (note.value == text) note.value = null
    }

    private fun render() {
        val tile = qsTile ?: return
        val c = container
        val t = c.guestTile.target.value
        val server = t?.let(::serverOf)
        val several = c.sessions.servers.value.size > 1
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_guest)
        val v = view.value
        val override = note.value
        when {
            t == null || server == null || v == View.NoTarget || v == View.NoServer -> {
                tile.label = getString(R.string.guest_tile_label)
                tile.subtitle = getString(R.string.guest_tile_pick)
                tile.state = Tile.STATE_INACTIVE
            }
            v == View.Locked -> {
                tile.label = getString(R.string.guest_tile_label)
                tile.subtitle = getString(R.string.widget_locked)
                tile.state = Tile.STATE_INACTIVE
            }
            else -> {
                val known = (v as? View.Known)?.guest
                tile.label = (known?.name ?: t.name).ifBlank { getString(R.string.guest_tile_id, t.vmid) }
                val stateText = when (v) {
                    is View.Known -> stateLine(v.guest, server.readOnly, v.stale)
                    View.Missing -> getString(R.string.guest_tile_missing)
                    is View.Failed -> getString(R.string.guest_tile_unreachable)
                    else -> getString(R.string.guest_tile_checking)
                }
                val line = override ?: stateText
                // With several servers, say which one the guest is on.
                tile.subtitle = if (several) getString(R.string.guest_tile_on_server, line, server.name) else line
                tile.state = when {
                    v == View.Missing || v is View.Failed -> Tile.STATE_UNAVAILABLE
                    known?.isRunning == true -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
            }
        }
        tile.contentDescription = listOfNotNull(tile.label, tile.subtitle).joinToString(", ")
        tile.updateTile()
    }

    private fun stateLine(g: Guest, readOnly: Boolean, stale: Boolean): String {
        val action = GuestTileLogic.tapAction(g)
        val base = when {
            g.isTemplate -> getString(R.string.guest_tile_template)
            g.lock.isNotEmpty() && !(g.isStopped && g.lock == "suspended") -> getString(R.string.guest_tile_locked_by, g.lock)
            readOnly || action == null -> getString(statusLabel(g))
            action == PowerAction.START -> getString(R.string.guest_tile_stopped_tap)
            else -> getString(R.string.guest_tile_running_tap)
        }
        return if (stale) getString(R.string.guest_tile_stale, base) else base
    }

    private fun statusLabel(g: Guest): Int = when {
        g.isRunning -> R.string.status_running
        g.isStopped -> R.string.status_stopped
        g.status == "paused" || g.status == "suspended" -> R.string.status_paused
        else -> R.string.status_unknown
    }

    /** Opens ProxCtrl, optionally with [action] for it to handle (the tile setting, or its own start/shut-down confirmation). */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp(action: String?, extras: Intent.() -> Unit = {}) {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(action ?: Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply(extras)
        val run = Runnable {
            val pi = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        }
        if (isLocked) unlockAndRun(run) else run.run()
    }

    private companion object {
        const val REFRESH_MS = 5_000L
        const val FAST_MS = 2_000L
        const val FAST_WINDOW_MS = 90_000L
        const val TASK_WAIT_MS = 120_000L
    }
}
