package com.vknxworks.proxctrl.data.server

import com.vknxworks.proxctrl.data.api.PveError
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import java.util.concurrent.ConcurrentHashMap

/**
 * Plain HTTP is allowed by the platform (a Proxmox node can sit behind a reverse proxy that speaks
 * HTTP on the home network), so ProxCtrl enforces it itself: an unencrypted request only goes out
 * to a server address the user chose after the "unencrypted connection" warning. That is a saved
 * server with an http:// address, or the address being set up right now once the user tapped
 * **Use HTTP**. Everything else must be HTTPS.
 *
 * Installed as a network interceptor on the shared client, so every hop is checked (also console
 * WebSockets, uploads and background checks): an HTTP hop is only allowed when the call itself was
 * started for an allowed HTTP address, so an HTTPS request can never end up on plain HTTP.
 */
object CleartextPolicy {
    /** Origins of saved servers with an http:// address. Replaced whenever the server list changes. */
    @Volatile
    private var saved: Set<String> = emptySet()

    /** Origins accepted in the setup screen that aren't saved (yet). */
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun origin(url: HttpUrl) = "${url.host.lowercase()}:${url.port}"

    /** Called by [ServerStore] with the saved servers, every time they change. */
    fun syncSaved(servers: List<Server>) {
        saved = servers.mapNotNull { s -> s.url.toHttpUrlOrNull()?.takeIf { !it.isHttps }?.let(::origin) }.toSet()
    }

    /** The user accepted plain HTTP for [url] in the setup screen. No-op for https addresses. */
    fun allow(url: HttpUrl) {
        if (!url.isHttps) pending += origin(url)
    }

    /** Forgets what was accepted in the setup screen (saved servers stay allowed). */
    fun clearPending() = pending.clear()

    fun isAllowed(url: HttpUrl): Boolean = url.isHttps || origin(url).let { it in saved || it in pending }

    val interceptor = Interceptor { chain ->
        val url = chain.request().url
        if (!url.isHttps) {
            val original = chain.call().request().url
            if (original.isHttps || !isAllowed(original) || !isAllowed(url)) throw PveError.CleartextBlocked(url.host)
        }
        chain.proceed(chain.request())
    }
}
