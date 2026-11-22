package com.vknxworks.proxctrl.ui.console

import android.os.Handler
import android.util.Base64
import android.webkit.JavascriptInterface
import com.vknxworks.proxctrl.data.console.ConsoleSession

/**
 * What the console pages can call, as `ProxBridge`. A named class (not an anonymous object) so the
 * methods WebView looks up by name survive shrinking in release builds.
 *
 * WebView calls these on its own background thread: socket sends go straight to the session (OkHttp
 * is thread-safe), everything that touches the screen is posted to the main thread.
 */
class ConsoleBridge(
    private val main: Handler,
    private val session: () -> ConsoleSession?,
    private val onPageReady: () -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onFailed: (String) -> Unit,
    private val onModsReleased: () -> Unit,
    /** True while the lock screen or a PIN screen covers the app. */
    private val inputBlocked: () -> Boolean = { false },
) {
    @JavascriptInterface
    fun pageReady() {
        main.post { onPageReady() }
    }

    @JavascriptInterface
    fun sendText(text: String) {
        // Typing (a terminal's "0:" data frame) never reaches the server while the app is covered by the
        // lock. Resizes and keep-alives still go through, so the session stays open behind the lock.
        if (text.startsWith("0:") && inputBlocked()) return
        session()?.sendText(text)
    }

    @JavascriptInterface
    fun sendBinary(b64: String) {
        session()?.sendBinary(Base64.decode(b64, Base64.NO_WRAP))
    }

    @JavascriptInterface
    fun close() {
        session()?.close()
    }

    @JavascriptInterface
    fun connected() {
        main.post { onConnected() }
    }

    @JavascriptInterface
    fun disconnected(how: String) {
        main.post { onDisconnected() }
    }

    @JavascriptInterface
    fun failed(message: String) {
        main.post { onFailed(message) }
    }

    @JavascriptInterface
    fun modsReleased() {
        main.post { onModsReleased() }
    }

    @JavascriptInterface
    fun title(name: String) = Unit

    @JavascriptInterface
    fun vncPassword(): String = session()?.vncPassword.orEmpty()
}
