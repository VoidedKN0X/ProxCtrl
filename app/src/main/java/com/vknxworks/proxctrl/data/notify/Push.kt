package com.vknxworks.proxctrl.data.notify

import android.content.Context
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
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Where ProxCtrl's UnifiedPush registration stands. */
sealed interface PushStatus {
    data object Off : PushStatus
    data object NoDistributor : PushStatus
    data object Registering : PushStatus
    data class Registered(val endpoint: String) : PushStatus
    data class Failed(val reason: String) : PushStatus
}

/**
 * UnifiedPush: a distributor app like ntfy gives ProxCtrl an endpoint URL; Proxmox's webhook
 * notification target posts to it, and the distributor hands the message to [ProxPushService].
 */
object Push {
    private const val PREFS = "push"
    private const val KEY_ENDPOINT = "endpoint"

    private val _status = MutableStateFlow<PushStatus>(PushStatus.Off)
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    fun load(context: Context, enabled: Boolean) {
        val endpoint = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENDPOINT, null)
        _status.value = when {
            !enabled -> PushStatus.Off
            UnifiedPush.getDistributors(context).isEmpty() -> PushStatus.NoDistributor
            endpoint != null -> PushStatus.Registered(endpoint)
            else -> PushStatus.Registering
        }
    }

    fun distributors(context: Context): List<String> = UnifiedPush.getDistributors(context)

    /** Registers with [distributor] (a package name, e.g. io.heckel.ntfy). */
    fun register(context: Context, distributor: String) {
        UnifiedPush.saveDistributor(context, distributor)
        _status.value = PushStatus.Registering
        UnifiedPush.register(context, messageForDistributor = context.getString(R.string.app_name))
    }

    fun unregister(context: Context) {
        runCatching { UnifiedPush.unregister(context) }
        runCatching { UnifiedPush.removeDistributor(context) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ENDPOINT).apply()
        _status.value = PushStatus.Off
    }

    internal fun onEndpoint(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ENDPOINT, url).apply()
        _status.value = PushStatus.Registered(url)
    }

    internal fun onFailed(reason: String) {
        _status.value = PushStatus.Failed(reason)
    }

    internal fun onUnregistered(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ENDPOINT).apply()
        _status.value = PushStatus.NoDistributor
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

/** Receives UnifiedPush events from the distributor (ntfy). */
class ProxPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        Push.onEndpoint(applicationContext, endpoint.url)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val c = applicationContext.container
        val text = message.content.toString(Charsets.UTF_8)
        c.appScope.launch {
            val settings = c.notifySettings.current()
            if (!settings.push) return@launch
            Push.toAlert(applicationContext, text, settings)?.let { Notifier.post(applicationContext, it, Notifier.CH_PROXMOX) }
            // Something happened on the server: refresh the background check too.
            NotifyWorker.runNow(applicationContext)
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Push.onFailed(reason.name)
    }

    override fun onUnregistered(instance: String) {
        Push.onUnregistered(applicationContext)
    }
}
