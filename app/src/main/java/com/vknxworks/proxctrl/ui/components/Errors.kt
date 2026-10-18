package com.vknxworks.proxctrl.ui.components

import android.content.Context
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.server.AuthMethod

/**
 * A message for [e] the user can act on, from strings.xml. [host] names the server in the message;
 * [auth] picks the right wording for a rejected sign-in.
 */
fun errorMessage(context: Context, e: Throwable, host: String, auth: AuthMethod? = null): String {
    val base = when (e) {
        is PveError.Unreachable -> context.getString(R.string.error_unreachable, host)
        is PveError.UntrustedCertificate -> context.getString(R.string.error_untrusted_cert, host)
        is PveError.PinMismatch -> context.getString(R.string.error_pin_mismatch, host)
        is PveError.Unauthorized -> context.getString(if (auth == AuthMethod.TICKET) R.string.error_bad_password else R.string.error_bad_token)
        is PveError.NeedsSignIn -> context.getString(R.string.error_session_ended, host)
        is PveError.Forbidden -> context.getString(R.string.error_forbidden)
        is PveError.NotProxmox -> context.getString(R.string.error_not_proxmox, host)
        is PveError.Server -> context.getString(R.string.error_server, e.code)
        else -> context.getString(R.string.error_generic)
    }
    // Proxmox's own wording helps for permission and server errors; network errors' detail is noise.
    val detail = when (e) {
        is PveError.Forbidden, is PveError.Server -> e.detail
        else -> null
    }
    return if (detail.isNullOrBlank()) base else context.getString(R.string.error_with_detail, base, detail)
}
