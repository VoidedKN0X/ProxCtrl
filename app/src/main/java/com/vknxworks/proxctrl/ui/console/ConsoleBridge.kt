package com.vknxworks.proxctrl.ui.console

import android.os.Handler
import android.util.Base64
import android.webkit.JavascriptInterface
import com.vknxworks.proxctrl.data.console.ConsoleSession
import java.util.concurrent.atomic.AtomicLong

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
    private val onLog: (String) -> Unit,
) {
    /** Calls from the page, for the diagnostics panel. */
    val pageReadyCalls = AtomicLong()
    val sendCalls = AtomicLong()
    val sendsWithoutSession = AtomicLong()

    @JavascriptInterface
    fun pageReady() {
        pageReadyCalls.incrementAndGet()
        main.post { onPageReady() }
    }

    @JavascriptInterface
    fun sendText(text: String) {
        sendCalls.incrementAndGet()
        val s = session() ?: run { sendsWithoutSession.incrementAndGet(); return }
        s.sendText(text)
    }

    @JavascriptInterface
    fun sendBinary(b64: String) {
        sendCalls.incrementAndGet()
        val s = session() ?: run { sendsWithoutSession.incrementAndGet(); return }
        s.sendBinary(Base64.decode(b64, Base64.NO_WRAP))
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

    @JavascriptInterface
    fun log(message: String) {
        main.post { onLog(message) }
    }
}
