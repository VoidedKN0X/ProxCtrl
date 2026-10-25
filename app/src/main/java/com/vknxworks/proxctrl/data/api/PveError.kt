package com.vknxworks.proxctrl.data.api

import com.vknxworks.proxctrl.data.server.Tls
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Everything that can go wrong talking to Proxmox, sorted into what the user can act on.
 * The UI turns these into messages from strings.xml; [detail] is Proxmox's own wording when it gave one.
 */
sealed class PveError(val detail: String? = null, cause: Throwable? = null) : IOException(detail, cause) {
    /** No route, DNS failure, refused or timed out. */
    class Unreachable(cause: Throwable?) : PveError(cause?.message, cause)

    /** The certificate isn't trusted by the phone and hasn't been pinned. */
    class UntrustedCertificate(cause: Throwable?) : PveError(cause?.message, cause)

    /** The server now presents a different certificate from the pinned one. */
    class PinMismatch(cause: Throwable?) : PveError(cause?.message, cause)

    /**
     * ProxCtrl refused to send a request over plain HTTP to [host]: only server addresses the user
     * chose after the "unencrypted connection" warning may use HTTP. Nothing was sent.
     */
    class CleartextBlocked(val host: String) : PveError(null)

    /** 401: wrong token or password, or the session ended. */
    class Unauthorized(detail: String?) : PveError(detail)

    /** 403: signed in, but this user or token lacks a privilege. */
    class Forbidden(detail: String?) : PveError(detail)

    /** The address answered, but not like a Proxmox VE API (e.g. a reverse proxy page). */
    class NotProxmox(detail: String?) : PveError(detail)

    /** The password session expired and needs a new two-factor code. */
    class NeedsSignIn : PveError(null)

    /**
     * The server is in read-only mode, so ProxCtrl refused to send a request that could change
     * something. Raised before anything is sent, whatever the screen that asked for it.
     */
    class ReadOnly : PveError(null)

    /** The user cancelled the fingerprint or screen-lock check before a change; nothing was sent. */
    class Cancelled : PveError(null)

    /** Any other error status from Proxmox (bad parameter, guest locked, …). */
    class Server(val code: Int, detail: String?) : PveError(detail)

    companion object {
        /** Sorts a network-level failure from OkHttp. */
        fun fromIo(e: IOException): PveError = when {
            e is PveError -> e
            Tls.isPinMismatch(e) -> PinMismatch(e)
            Tls.isCertificateError(e) -> UntrustedCertificate(e)
            e is SSLHandshakeException -> UntrustedCertificate(e)
            e is UnknownHostException || e is ConnectException || e is NoRouteToHostException || e is SocketTimeoutException -> Unreachable(e)
            else -> Unreachable(e)
        }
    }
}
