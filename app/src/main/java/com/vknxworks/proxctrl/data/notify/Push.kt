package com.vknxworks.proxctrl.data.notify

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveJson
import com.vknxworks.proxctrl.data.guests.long
import com.vknxworks.proxctrl.data.guests.str
import com.vknxworks.proxctrl.data.overview.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where ProxCtrl's registration with ntfy stands. */
sealed interface PushStatus {
    data object Off : PushStatus
    /** Push is on, but ntfy isn't installed (any more). */
    data object NoNtfy : PushStatus
    data object Registering : PushStatus
    data class Registered(val endpoint: String) : PushStatus
    /** ntfy's server is down for now; the address stays the same and ntfy says when it's back. */
    data class Unavailable(val endpoint: String) : PushStatus
    data class Failed(val reason: String) : PushStatus
}

/**
 * Push through ntfy only: ntfy gives ProxCtrl an endpoint URL on your ntfy server; Proxmox's webhook
 * notification target posts to it, and ntfy hands the message to [PushReceiver].
 */
object Push {
    private const val PREFS = "push"
    private const val KEY_ENDPOINT = "endpoint"

    private val _status = MutableStateFlow<PushStatus>(PushStatus.Off)
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context, enabled: Boolean) {
        val endpoint = prefs(context).getString(KEY_ENDPOINT, null)
        val unavailable = UnifiedPush.unavailable.value
        _status.value = when {
            !enabled -> PushStatus.Off
            !UnifiedPush.isNtfyInstalled(context) -> PushStatus.NoNtfy
            endpoint != null && unavailable -> PushStatus.Unavailable(endpoint)
            endpoint != null -> PushStatus.Registered(endpoint)
            _status.value is PushStatus.Failed -> _status.value
            else -> PushStatus.Registering
        }
    }

    /**
     * At start-up: takes over a registration from an earlier version (kept as it is with ntfy, removed with any
     * other push app) and registers with ntfy again, which keeps the same address and recovers from a reinstalled
     * ntfy. Returns false when push had to be turned off because ntfy isn't installed.
     */
    fun start(context: Context, enabled: Boolean): Boolean {
        val previous = UnifiedPush.migrateFromLibrary(context)
        if (enabled && previous != null && previous != UnifiedPush.NTFY) {
            // The address came from another push app: Proxmox needs the new one from ntfy.
            prefs(context).edit().remove(KEY_ENDPOINT).apply()
            if (!UnifiedPush.isNtfyInstalled(context)) {
                _status.value = PushStatus.Off
                return false
            }
        }
        load(context, enabled)
        if (enabled && UnifiedPush.isNtfyInstalled(context)) UnifiedPush.register(context, context.getString(R.string.app_name))
        return true
    }

    /** Asks ntfy for an address. Returns false (status [PushStatus.NoNtfy]) when ntfy isn't installed. */
    fun register(context: Context): Boolean {
        if (!UnifiedPush.register(context, context.getString(R.string.app_name))) {
            _status.value = PushStatus.NoNtfy
            return false
        }
        if (_status.value !is PushStatus.Registered) _status.value = PushStatus.Registering
        return true
    }

    fun unregister(context: Context) {
        runCatching { UnifiedPush.unregister(context) }
        prefs(context).edit().remove(KEY_ENDPOINT).apply()
        _status.value = PushStatus.Off
    }

    internal fun onEndpoint(context: Context, url: String) {
        prefs(context).edit().putString(KEY_ENDPOINT, url).apply()
        UnifiedPush.onEndpoint(context)
        _status.value = PushStatus.Registered(url)
    }

    internal fun onFailed(context: Context, reason: String) {
        UnifiedPush.onLost(context, reason)
        _status.value = PushStatus.Failed(reason)
    }

    internal fun onUnregistered(context: Context) {
        UnifiedPush.onLost(context, null)
        prefs(context).edit().remove(KEY_ENDPOINT).apply()
        _status.value = PushStatus.Failed("UNREGISTERED")
    }

    internal fun onTempUnavailable(context: Context) {
        UnifiedPush.onTempUnavailable()
        prefs(context).getString(KEY_ENDPOINT, null)?.let { _status.value = PushStatus.Unavailable(it) }
    }

    internal fun onMessage(context: Context) {
        UnifiedPush.onMessage()
        val st = _status.value
        if (st is PushStatus.Unavailable) _status.value = PushStatus.Registered(st.endpoint)
    }

    /**
     * The JSON body Proxmox sends. Only the title and metadata, not the full message: backup reports
     * can be longer than push services accept, and ProxCtrl shows the details in the app anyway.
     */
    const val BODY_TEMPLATE = """{"title":{{ json title }},"severity":{{ json severity }},"timestamp":{{ json timestamp }},"fields":{{ json fields }}}"""

    /**
     * The endpoint split into its public part (scheme, host, port), the secret path (the ntfy topic,
     * which anyone who knows it can post to) and the query (e.g. up=1). The path goes into a Proxmox
     * webhook secret, which only root can read, instead of the URL shown in the Proxmox UI.
     */
    data class EndpointParts(val base: String, val path: String, val query: String)

    fun splitEndpoint(endpoint: String): EndpointParts? {
        val url = endpoint.toHttpUrlOrNull() ?: return null
        val port = if (url.port != okhttp3.HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""
        return EndpointParts(
            base = "${url.scheme}://${url.host}$port",
            path = url.encodedPath.trimStart('/'),
            query = url.encodedQuery.orEmpty(),
        )
    }

    private fun String.oneLine(max: Int) = replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(max)

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

    /** Shell commands for the Proxmox node that set up the webhook target and the matchers. */
    fun setupCommands(endpoint: String): String {
        val parts = splitEndpoint(endpoint) ?: EndpointParts(endpoint, "", "")
        val query = if (parts.query.isEmpty()) "" else "?${parts.query}"
        val url = if (parts.path.isEmpty()) endpoint else "${parts.base}/{{ secrets.path }}$query"
        val secret = if (parts.path.isEmpty()) "" else " \\\n  --secret 'name=path,value=${b64(parts.path)}'"
        return """
pvesh create /cluster/notifications/endpoints/webhook --name proxctrl \
  --method post --url '$url' \
  --header 'name=Content-Type,value=${b64("application/json")}' \
  --body '${b64(BODY_TEMPLATE)}'$secret \
  --comment 'ProxCtrl push notifications'

pvesh create /cluster/notifications/matchers --name proxctrl-backups \
  --target proxctrl --match-field exact:type=vzdump --match-severity error \
  --comment 'ProxCtrl: failed backups'

pvesh create /cluster/notifications/matchers --name proxctrl-cluster \
  --target proxctrl --match-field exact:type=fencing,replication \
  --comment 'ProxCtrl: fencing and replication'

pvesh create /cluster/notifications/matchers --name proxctrl-updates \
  --target proxctrl --match-field exact:type=package-updates \
  --comment 'ProxCtrl: available updates'

pvesh create /cluster/notifications/matchers --name proxctrl-system-mail \
  --target proxctrl --match-field exact:type=system-mail \
  --comment 'ProxCtrl: ZFS and SMART warnings'

pvesh create /cluster/notifications/targets/proxctrl/test
""".trim()
    }

    /** Turns a push from Proxmox into a notification, or null when its kind is switched off. */
    fun toAlert(context: Context, content: String, settings: NotifySettings): Alert? {
        val o = runCatching { PveJson.parseToJsonElement(content) as? JsonObject }.getOrNull()
        val fields = o?.get("fields") as? JsonObject
        val type = fields?.str("type").orEmpty()
        // Anyone who learns the push address can post to it: keep what's shown short and on one line.
        val host = fields?.str("hostname").orEmpty().oneLine(80)
        val title = (o?.str("title")?.takeIf { it.isNotBlank() } ?: content.lineSequence().firstOrNull().orEmpty()).oneLine(200)
        val severity = when (o?.str("severity")) {
            "error" -> Severity.CRITICAL
            "warning" -> Severity.WARNING
            else -> Severity.INFO
        }
        val event = when (type) {
            "vzdump" -> NotifyEvent.BACKUP_FAILED
            "package-updates" -> NotifyEvent.UPDATES
            "fencing" -> NotifyEvent.NODE_UNREACHABLE
            "replication" -> NotifyEvent.TASK_FAILED
            else -> null
        }
        if (event != null && !settings.on(event)) return null
        if (type == "system-mail" && !settings.on(NotifyEvent.ZFS_DEGRADED) && !settings.on(NotifyEvent.SMART_FAILED)) return null
        val ts = o?.long("timestamp") ?: (System.currentTimeMillis() / 1000)
        return Alert(
            key = "push:$type:$host:$ts",
            event = event,
            severity = severity,
            title = title.ifBlank { context.getString(R.string.nt_push_fallback) },
            text = if (host.isNotBlank()) context.getString(R.string.nt_push_from, host) else context.getString(R.string.nt_push_fallback),
        )
    }
}

/**
 * Receives what ntfy hands over: the push address, registration errors and Proxmox's notifications. Exported
 * because ntfy is another app, so every broadcast must carry the secret token of ProxCtrl's registration (and,
 * from Android 14, come from ntfy) or it's ignored. Anyone who knows the push address can post to it, so
 * [Push.toAlert] keeps what's shown short.
 */
class PushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(UnifiedPush.EXTRA_TOKEN) ?: return
        if (token != UnifiedPush.token(context)) return
        if (Build.VERSION.SDK_INT >= 34) {
            val sender = sentFromPackage
            if (sender != null && sender != UnifiedPush.NTFY) return
        }
        val app = context.applicationContext
        when (intent.action) {
            UnifiedPush.ACTION_NEW_ENDPOINT -> {
                val endpoint = intent.getStringExtra(UnifiedPush.EXTRA_ENDPOINT) ?: return
                UnifiedPush.ack(app, token, intent.getStringExtra(UnifiedPush.EXTRA_ID))
                Push.onEndpoint(app, endpoint)
            }
            UnifiedPush.ACTION_REGISTRATION_FAILED -> Push.onFailed(app, intent.getStringExtra(UnifiedPush.EXTRA_REASON) ?: "UNKNOWN")
            UnifiedPush.ACTION_UNREGISTERED -> Push.onUnregistered(app)
            UnifiedPush.ACTION_TEMP_UNAVAILABLE -> Push.onTempUnavailable(app)
            UnifiedPush.ACTION_MESSAGE -> {
                val bytes = intent.getByteArrayExtra(UnifiedPush.EXTRA_BYTES_MESSAGE)
                    ?: intent.getStringExtra(UnifiedPush.EXTRA_MESSAGE)?.toByteArray()
                UnifiedPush.ack(app, token, intent.getStringExtra(UnifiedPush.EXTRA_ID))
                Push.onMessage(app)
                if (bytes == null) return
                val text = bytes.toString(Charsets.UTF_8)
                val c = app.container
                val pending = goAsync()
                c.appScope.launch {
                    try {
                        val settings = c.notifySettings.current()
                        if (!settings.push) return@launch
                        Push.toAlert(app, text, settings)?.let { Notifier.post(app, it, Notifier.CH_PROXMOX) }
                        // Something happened on the server: refresh the background check too.
                        NotifyWorker.runNow(app)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

/**
 * ntfy binds to this for a few seconds before it delivers a message. That lifts ProxCtrl to foreground
 * importance, so the notification can be handled straight away even while the phone is in deep sleep.
 */
class RaiseToForegroundService : Service() {
    private val binder = Binder()
    override fun onBind(intent: Intent?): IBinder = binder
}
