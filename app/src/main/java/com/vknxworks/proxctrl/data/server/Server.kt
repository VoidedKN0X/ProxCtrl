package com.vknxworks.proxctrl.data.server

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How ProxCtrl signs in to a Proxmox server. */
@Serializable
enum class AuthMethod { TOKEN, TICKET }

/**
 * A saved Proxmox server (a single node or a cluster). Secrets (token secret, password, ticket)
 * are never part of this object: they are stored separately, encrypted with a Keystore key.
 */
@Serializable
data class Server(
    val id: String,
    /** What the user calls it; defaults to the cluster or node name. */
    val name: String,
    /** Base address without a path, e.g. https://192.168.1.10:8006. */
    val url: String,
    val auth: AuthMethod,
    /** Token ID for [AuthMethod.TOKEN], e.g. proxctrl@pve!phone. */
    val tokenId: String = "",
    /** user@realm for [AuthMethod.TICKET]. */
    val username: String = "",
    /** SHA-256 fingerprint of the pinned self-signed certificate; empty when the certificate is trusted normally. */
    val certPin: String = "",
    /** Hide every action that changes something on this server. */
    val readOnly: Boolean = false,
    /** The account needs a second factor (TOTP) to sign in, so the app can't renew an expired session silently. */
    val usesTfa: Boolean = false,
    /** Last seen Proxmox VE version, e.g. 9.2.18. */
    val pveVersion: String = "",
) {
    val host: String get() = url.toHttpUrlOrNull()?.host ?: url

    /** The user this server's API calls run as (the token ID for tokens). */
    val authId: String get() = if (auth == AuthMethod.TOKEN) tokenId else username

    /** First letter shown in the profile picture. */
    val initial: String get() = name.ifBlank { host }.take(1)
}

/** Secrets for one server. Held in memory only while needed; persisted encrypted by [ServerStore]. */
data class ServerSecrets(
    val tokenSecret: String = "",
    val password: String = "",
    val ticket: String = "",
    val csrf: String = "",
    /** When [ticket] was issued (ms since epoch). Proxmox tickets are valid for 2 hours. */
    val ticketIssuedAt: Long = 0,
) {
    /** Never prints the secrets themselves, should this object end up in a log or error message. */
    override fun toString(): String = "ServerSecrets(redacted)"
}

object ServerAddress {
    const val DEFAULT_PORT = 8006

    /**
     * Turns what the user typed into a base URL. No scheme and no port means a Proxmox node on
     * its usual port 8006 ("192.168.1.10" → https://192.168.1.10:8006). An explicit https:// address
     * without a port keeps the standard port 443, which is what a reverse proxy like Nginx Proxy
     * Manager uses. Paths are dropped. Returns null for anything that isn't a usable https address.
     */
    fun normalize(input: String): HttpUrl? {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty()) return null
        if (text.startsWith("http://", ignoreCase = true)) return null
        val hasScheme = text.startsWith("https://", ignoreCase = true)
        val withScheme = if (hasScheme) text else "https://$text"
        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        val authority = withScheme.substringAfter("://").substringBefore('/')
        val typedPort = authority.substringAfterLast(']', authority).contains(':')
        val port = when {
            typedPort -> parsed.port
            hasScheme -> 443
            else -> DEFAULT_PORT
        }
        return HttpUrl.Builder().scheme("https").host(parsed.host).port(port).build()
    }

    /** The base URL as stored: no trailing slash. */
    fun base(url: HttpUrl): String = url.toString().trimEnd('/')

    fun isPlainHttp(input: String): Boolean = input.trim().startsWith("http://", ignoreCase = true)
}
