package com.vknxworks.proxctrl.data

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Links other apps can open ProxCtrl with. `proxctrl://guest/<vmid>` opens that guest's page on the
 * active server (TrueCtrl uses it to offer starting its TrueNAS VM again after a shutdown). A link
 * only opens a page; it never starts, stops or changes anything.
 */
object DeepLinks {
    private val _pendingGuest = MutableStateFlow<Int?>(null)

    /** A guest ID waiting to be opened, or null. */
    val pendingGuest: StateFlow<Int?> = _pendingGuest.asStateFlow()

    fun handle(uri: Uri?) {
        if (uri == null || uri.scheme != "proxctrl" || uri.host != "guest") return
        val vmid = uri.pathSegments.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 } ?: return
        _pendingGuest.value = vmid
    }

    fun consumed() {
        _pendingGuest.value = null
    }
}
