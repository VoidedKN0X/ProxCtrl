package com.vknxworks.proxctrl.data.api

import com.vknxworks.proxctrl.data.server.AuthMethod
import com.vknxworks.proxctrl.data.server.Server
import com.vknxworks.proxctrl.data.server.ServerSecrets
import com.vknxworks.proxctrl.data.server.Tls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/** Lenient JSON for Proxmox responses: unknown fields ignored, nulls fall back to defaults. */
val PveJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * OkHttp client for one server: normal certificate checks, or only the pinned certificate when the
 * user confirmed a self-signed one (never "trust all").
 */
fun httpFor(base: OkHttpClient, host: String, certPin: String): OkHttpClient {
    if (certPin.isBlank()) return base
    val tm = Tls.PinnedTrustManager(certPin, Tls.systemTrustManager())
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), null) }
    return base.newBuilder()
        .sslSocketFactory(ctx.socketFactory, tm)
        .hostnameVerifier(Tls.pinnedHostnameVerifier(certPin, host))
        .build()
}

/**
 * Proxmox VE REST client (https://<host>:8006/api2/json). Signs requests with the API token, or with
 * the PVEAuthCookie ticket plus CSRFPreventionToken on writes. Tickets last two hours: they are renewed
 * after an hour, and re-created from the saved password when needed (accounts with two-factor sign-in
 * have to sign in again instead).
 */
class PveClient(
    val server: Server,
    base: OkHttpClient,
    secrets: ServerSecrets,
    /**
     * Whether the server is in read-only mode right now. Read on every request (not copied from
     * [server] once), so switching read-only on also stops clients a screen is still holding.
     */
    private val isReadOnly: () -> Boolean = { server.readOnly },
    /** Called when a ticket is renewed, so it can be saved (encrypted). */
    private val onSecretsChanged: (ServerSecrets) -> Unit = {},
) {
    val http: OkHttpClient = httpFor(base, server.host, server.certPin)
    private val apiBase: HttpUrl = "${server.url}/api2/json".toHttpUrl()
    private val ticketLock = Mutex()

    @Volatile
    var secrets: ServerSecrets = secrets
        private set

    fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val b = apiBase.newBuilder()
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement = call("GET", path, query, emptyMap())
    suspend fun post(path: String, params: Map<String, String> = emptyMap()): JsonElement = call("POST", path, emptyMap(), params)
    suspend fun put(path: String, params: Map<String, String> = emptyMap()): JsonElement = call("PUT", path, emptyMap(), params)
    suspend fun delete(path: String, query: Map<String, String> = emptyMap()): JsonElement = call("DELETE", path, query, emptyMap())

    suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>, query: Map<String, String> = emptyMap()): T =
        PveJson.decodeFromJsonElement(strategy, get(path, query))

    private suspend fun call(method: String, path: String, query: Map<String, String>, params: Map<String, String>): JsonElement {
        // Read-only mode is enforced here, for every request that could change something (power
        // actions, notes, consoles and anything added later), not only by hiding buttons.
        if (method != "GET" && isReadOnly()) throw PveError.ReadOnly()
        if (server.auth == AuthMethod.TICKET) ensureFreshTicket()
        return try {
            execute(method, path, query, params)
        } catch (e: PveError.Unauthorized) {
            // A ticket can be revoked or expire early (e.g. the node rebooted with a new auth key): sign in once more.
            if (server.auth != AuthMethod.TICKET) throw e
            relogin()
            execute(method, path, query, params)
        }
    }

    private suspend fun execute(method: String, path: String, query: Map<String, String>, params: Map<String, String>): JsonElement =
        withContext(Dispatchers.IO) {
            val body = if (method == "GET" || method == "DELETE") null
            else FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
            val request = Request.Builder().url(url(path, query)).method(method, body).also { authorize(it, method) }.build()
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw PveError.fromIo(e)
            }
            response.use { parse(it) }
        }

    private fun authorize(b: Request.Builder, method: String) {
        val s = secrets
        when (server.auth) {
            AuthMethod.TOKEN -> b.header("Authorization", "PVEAPIToken=${server.tokenId}=${s.tokenSecret}")
            AuthMethod.TICKET -> {
                b.header("Cookie", "PVEAuthCookie=${s.ticket}")
                if (method != "GET") b.header("CSRFPreventionToken", s.csrf)
            }
        }
    }

    /**
     * Headers that sign a WebSocket upgrade (consoles). WebSockets are always opened with GET, so no
     * CSRF token is needed; a ticket is renewed first if it's getting old.
     */
    suspend fun webSocketHeaders(): Map<String, String> {
        if (server.auth == AuthMethod.TICKET) ensureFreshTicket()
        val s = secrets
        return when (server.auth) {
            AuthMethod.TOKEN -> mapOf("Authorization" to "PVEAPIToken=${server.tokenId}=${s.tokenSecret}")
            AuthMethod.TICKET -> mapOf("Cookie" to "PVEAuthCookie=${s.ticket}")
        }
    }

    // --- Tickets ---

    private suspend fun ensureFreshTicket() {
        val age = System.currentTimeMillis() - secrets.ticketIssuedAt
        if (secrets.ticket.isNotEmpty() && age < RENEW_AFTER_MS) return
        ticketLock.withLock {
            val s = secrets
            val nowAge = System.currentTimeMillis() - s.ticketIssuedAt
            if (s.ticket.isNotEmpty() && nowAge < RENEW_AFTER_MS) return
            if (s.ticket.isNotEmpty() && nowAge < TICKET_LIFETIME_MS) {
                // A valid ticket can be exchanged for a new one by sending it as the password.
                val renewed = runCatching { Auth.requestTicket(http, server.url, server.username, s.ticket) }.getOrNull()
                if (renewed != null && renewed.needTfa == 0 && renewed.ticket.isNotEmpty()) {
                    store(renewed)
                    return
                }
            }
            reloginLocked()
        }
    }

    private suspend fun relogin() = ticketLock.withLock { reloginLocked() }

    private suspend fun reloginLocked() {
        val s = secrets
        if (server.usesTfa || s.password.isEmpty()) throw PveError.NeedsSignIn()
        val res = Auth.requestTicket(http, server.url, server.username, s.password)
        if (res.needTfa != 0 || res.ticket.isEmpty()) throw PveError.NeedsSignIn()
        store(res)
    }

    private fun store(res: TicketResponse) {
        val updated = secrets.copy(ticket = res.ticket, csrf = res.csrf, ticketIssuedAt = System.currentTimeMillis())
        secrets = updated
        onSecretsChanged(updated)
    }

    companion object {
        /** Proxmox tickets are valid for 2 hours; renew well before that. */
        const val TICKET_LIFETIME_MS = 2 * 60 * 60 * 1000L
        const val RENEW_AFTER_MS = 60 * 60 * 1000L

        /** Reads a Proxmox response: the `data` member on success, a [PveError] otherwise. */
        fun parse(response: Response): JsonElement {
            val text = response.body?.string().orEmpty()
            // Proxmox puts its error text in the JSON "message" member and in the HTTP status line
            // ("Permission check failed (…)"); the status line is lost through HTTP/2 proxies.
            val reason = messageFrom(text) ?: response.message.takeIf { it.isNotBlank() }
            if (!response.isSuccessful) {
                val detail = reason ?: errorsFrom(text)
                throw when (response.code) {
                    401 -> PveError.Unauthorized(detail)
                    403 -> PveError.Forbidden(detail)
                    else -> {
                        val json = runCatching { PveJson.parseToJsonElement(text) }.getOrNull()
                        if (json == null && text.isNotBlank()) PveError.NotProxmox(detail) else PveError.Server(response.code, errorsFrom(text) ?: detail)
                    }
                }
            }
            val root = runCatching { PveJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: throw PveError.NotProxmox(reason)
            return root["data"] ?: JsonNull
        }

        private fun messageFrom(text: String): String? = runCatching {
            (PveJson.parseToJsonElement(text).jsonObject["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

        /** Parameter errors come back as {"errors": {"field": "message"}}. */
        private fun errorsFrom(text: String): String? = runCatching {
            PveJson.parseToJsonElement(text).jsonObject["errors"]?.jsonObject
                ?.entries?.joinToString("; ") { (k, v) -> "$k: ${v.toString().trim('"')}" }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
