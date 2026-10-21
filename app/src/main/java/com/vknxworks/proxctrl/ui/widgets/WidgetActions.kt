package com.vknxworks.proxctrl.ui.widgets

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.guests.PowerAction
import com.vknxworks.proxctrl.data.guests.power
import com.vknxworks.proxctrl.data.widgets.WidgetRefresher
import com.vknxworks.proxctrl.ui.components.errorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Requests that come from the widgets and the tile. A widget button never acts by itself: it opens
 * the app, which asks for confirmation here.
 */
object WidgetActions {
    const val ACTION_CONFIRM_POWER = "com.vknxworks.proxctrl.action.WIDGET_POWER"
    const val ACTION_OPEN_OVERVIEW = "com.vknxworks.proxctrl.action.OPEN_OVERVIEW"
    const val EXTRA_SERVER = "server"
    const val EXTRA_VMID = "vmid"
    const val EXTRA_ACTION = "action"
    const val EXTRA_INTERNAL = "internal"
    const val START = "start"
    const val SHUTDOWN = "shutdown"

    data class PowerRequest(val serverId: String, val vmid: Int, val action: String)

    private val _pending = MutableStateFlow<PowerRequest?>(null)

    /** A start or shut-down asked for from the pinned-guests widget, waiting for confirmation. */
    val pending: StateFlow<PowerRequest?> = _pending.asStateFlow()

    private val _openOverview = MutableStateFlow(false)

    /**
     * True when the tile or the node widget asked for the Overview. The navigation host shows the
     * Overview tab and calls [overviewShown].
     */
    val openOverview: StateFlow<Boolean> = _openOverview.asStateFlow()

    /**
     * A random value only ProxCtrl's own widgets carry. MainActivity is exported, so other apps can open it
     * too, but without this they can't open it straight into the "Start/Shut down?" question.
     */
    fun internalToken(context: Context): String {
        val prefs = context.getSharedPreferences("widget_internal", Context.MODE_PRIVATE)
        return prefs.getString("token", null)
            ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("token", it).apply() }
    }

    /** Reads an intent MainActivity was started or resumed with. */
    fun handle(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_CONFIRM_POWER -> {
                if (intent.getStringExtra(EXTRA_INTERNAL) != internalToken(context)) return
                val server = intent.getStringExtra(EXTRA_SERVER) ?: return
                val vmid = intent.getIntExtra(EXTRA_VMID, 0).takeIf { it > 0 } ?: return
                val action = intent.getStringExtra(EXTRA_ACTION)?.takeIf { it == START || it == SHUTDOWN } ?: return
                _pending.value = PowerRequest(server, vmid, action)
            }
            ACTION_OPEN_OVERVIEW -> _openOverview.value = true
        }
    }

    fun dismiss() {
        _pending.value = null
    }

    fun overviewShown() {
        _openOverview.value = false
    }
}

/** The confirmation for a widget's Start or Shut down button. Shown on top of whatever screen is open. */
@Composable
fun WidgetActionConfirm() {
    val context = LocalContext.current
    val c = context.container
    val request by WidgetActions.pending.collectAsStateWithLifecycle()
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val list by c.guests.state.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf(false) }
    val r = request ?: return
    val server = active?.server
    // Opened from the home screen right after start-up, the guest list may still be loading.
    LaunchedEffect(r) {
        if (c.guests.state.value.guests.none { it.vmid == r.vmid }) runCatching { c.guests.refresh() }
    }

    // The widget was set up for another server than the active one: say so instead of acting there.
    if (server == null || server.id != r.serverId) {
        AlertDialog(
            onDismissRequest = WidgetActions::dismiss,
            title = { Text(stringResource(R.string.widget_confirm_other_server_title)) },
            text = { Text(stringResource(R.string.widget_confirm_other_server_body)) },
            confirmButton = { TextButton(onClick = WidgetActions::dismiss) { Text(stringResource(R.string.done)) } },
        )
        return
    }
    if (server.readOnly) {
        AlertDialog(
            onDismissRequest = WidgetActions::dismiss,
            title = { Text(stringResource(R.string.widget_confirm_read_only_title)) },
            text = { Text(stringResource(R.string.error_read_only)) },
            confirmButton = { TextButton(onClick = WidgetActions::dismiss) { Text(stringResource(R.string.done)) } },
        )
        return
    }

    val guest: Guest? = list.guests.firstOrNull { it.vmid == r.vmid }
    val action = if (r.action == WidgetActions.START) PowerAction.START else PowerAction.SHUTDOWN
    val name = guest?.let { "${it.vmid} ${it.name}".trim() } ?: r.vmid.toString()
    val applies = guest != null && action.appliesTo(guest)
    val allowed = c.sessions.canWrite("/vms/${r.vmid}", PowerAction.PRIV)

    AlertDialog(
        onDismissRequest = { if (!running) WidgetActions.dismiss() },
        title = {
            Text(
                stringResource(if (action == PowerAction.START) R.string.widget_confirm_start_title else R.string.widget_confirm_shutdown_title, name),
            )
        },
        text = {
            Text(
                when {
                    guest == null -> stringResource(R.string.widget_confirm_unknown_guest)
                    !allowed -> stringResource(R.string.not_allowed_action)
                    !applies -> stringResource(R.string.widget_confirm_not_applicable)
                    action == PowerAction.SHUTDOWN -> stringResource(R.string.widget_confirm_shutdown_body)
                    else -> stringResource(R.string.widget_confirm_start_body)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(
                enabled = guest != null && applies && allowed && !running,
                onClick = {
                    val g = guest ?: return@TextButton
                    val client = active?.client ?: return@TextButton
                    running = true
                    c.appScope.launch {
                        val result = client.power(g, action)
                        val e = result.error
                        val label = context.getString(if (action == PowerAction.START) R.string.widget_start else R.string.widget_shutdown)
                        val message = if (e == null) {
                            context.getString(R.string.widget_confirm_sent, label, name)
                        } else {
                            context.getString(R.string.widget_confirm_failed, label, errorMessage(context, e, server.host, server.auth))
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            running = false
                            WidgetActions.dismiss()
                        }
                        // Let the guest change state, then redraw the widgets and refresh the list.
                        WidgetRefresher.refreshSoon(context.applicationContext, delaySeconds = 8)
                        runCatching { c.guests.refresh() }
                    }
                },
            ) {
                Text(stringResource(if (action == PowerAction.START) R.string.widget_start else R.string.widget_shutdown))
            }
        },
        dismissButton = {
            TextButton(onClick = WidgetActions::dismiss, enabled = !running) { Text(stringResource(R.string.cancel)) }
        },
    )
}
