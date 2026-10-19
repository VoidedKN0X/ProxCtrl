package com.vknxworks.proxctrl.ui.console

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.console.ConsoleSession
import com.vknxworks.proxctrl.data.console.ConsoleTarget
import com.vknxworks.proxctrl.data.server.AuthMethod
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val ASSET_HOST = "https://appassets.androidplatform.net"

private enum class ConsoleState { CONNECTING, CONNECTED, CLOSED, FAILED }

/**
 * Full-screen console: a container's or node's terminal (xterm.js), or a VM's screen (noVNC).
 * The pages are bundled with the app; the connection itself is made by [ConsoleSession].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ConsoleScreen(target: ConsoleTarget, title: String) {
    val context = LocalContext.current
    val activity = context as? Activity
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val server = active?.server
    val main = remember { Handler(Looper.getMainLooper()) }

    var state by remember { mutableStateOf(ConsoleState.CONNECTING) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var superKey by remember { mutableStateOf(false) }
    var fontSize by remember { mutableIntStateOf(13) }
    var landscape by remember { mutableStateOf(false) }
    var keyboardShown by remember { mutableStateOf(false) }
    var showLoginNote by remember {
        mutableStateOf(target is ConsoleTarget.NodeShell && !(server?.auth == AuthMethod.TICKET && server.username == "root@pam"))
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var session by remember { mutableStateOf<ConsoleSession?>(null) }

    fun js(code: String) {
        main.post { webView?.evaluateJavascript(code, null) }
    }

    // Full screen while the console is open; restore bars and rotation when leaving.
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            session?.close()
        }
    }

    val listener = remember {
        object : ConsoleSession.Listener {
            override fun onOpen() = js("pcOpen()")
            override fun onBytes(bytes: ByteArray) = js("pcRecv('${Base64.encodeToString(bytes, Base64.NO_WRAP)}')")
            override fun onClosed(code: Int, reason: String) {
                js("pcClosed($code, ${JSONObject.quote(reason)})")
                main.post { if (state != ConsoleState.FAILED) state = ConsoleState.CLOSED }
            }
            override fun onFailure(error: Throwable) {
                js("pcError('socket')")
                main.post {
                    state = ConsoleState.FAILED
                    errorMessageFor(error)
                }
            }
            private fun errorMessageFor(e: Throwable) {
                error = errorMessage(context, e, server?.host.orEmpty(), server?.auth)
            }
        }
    }

    fun connect() {
        val client = active?.client ?: return
        session?.close()
        val s = ConsoleSession(client, target)
        session = s
        state = ConsoleState.CONNECTING
        error = null
        scope.launch {
            try {
                s.connect(listener)
            } catch (e: Exception) {
                state = ConsoleState.FAILED
                error = errorMessage(context, e, server?.host.orEmpty(), server?.auth).let { m ->
                    if (e is PveError.Server && (e.detail?.contains("not running", true) == true)) context.getString(R.string.console_not_running) else m
                }
            }
        }
    }

    /** What the console pages can call. Every method only acts on this console's own session. */
    val bridge = remember {
        object {
            @JavascriptInterface fun pageReady() = main.post { connect() }.let { }
            @JavascriptInterface fun sendText(text: String) = session?.sendText(text).let { }
            @JavascriptInterface fun sendBinary(b64: String) = session?.sendBinary(Base64.decode(b64, Base64.NO_WRAP)).let { }
            @JavascriptInterface fun close() = session?.close().let { }
            @JavascriptInterface fun connected() = main.post { state = ConsoleState.CONNECTED }.let { }
            @JavascriptInterface fun disconnected(how: String) = main.post { if (state != ConsoleState.FAILED) state = ConsoleState.CLOSED }.let { }
            @JavascriptInterface fun failed(message: String) = main.post {
                state = ConsoleState.FAILED
                error = context.getString(R.string.console_failed, message)
            }.let { }
            @JavascriptInterface fun modsReleased() = main.post { ctrl = false; alt = false; superKey = false }.let { }
            @JavascriptInterface fun title(name: String) = Unit
            @JavascriptInterface fun vncPassword(): String = session?.vncPassword.orEmpty()
        }
    }

    fun reconnect() {
        attempt++
        state = ConsoleState.CONNECTING
        webView?.reload()
    }

    fun sendMods() {
        if (target.isScreen) js("pcSetMods($ctrl, $alt, $superKey)") else js("pcSetMods($ctrl, $alt)")
    }

    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0D0F12)).imePadding()) {
        // Compact bar: back, title, and tools.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = androidx.compose.ui.graphics.Color.White) }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = androidx.compose.ui.graphics.Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!target.isScreen) {
                IconButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(8); js("pcFontSize($fontSize)") }) {
                    Icon(Icons.Filled.TextDecrease, stringResource(R.string.console_font_smaller), tint = androidx.compose.ui.graphics.Color.White)
                }
                IconButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(28); js("pcFontSize($fontSize)") }) {
                    Icon(Icons.Filled.TextIncrease, stringResource(R.string.console_font_bigger), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            IconButton(onClick = {
                landscape = !landscape
                activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }) { Icon(Icons.Filled.ScreenRotation, stringResource(R.string.console_rotate), tint = androidx.compose.ui.graphics.Color.White) }
        }

        if (showLoginNote) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.console_token_login), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showLoginNote = false }) { Text(stringResource(R.string.console_dismiss)) }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val loader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                        .build()
                    WebView(ctx).apply {
                        setBackgroundColor(Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportZoom(false)
                        settings.mediaPlaybackRequiresUserGesture = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                loader.shouldInterceptRequest(request.url)

                            // Only the bundled pages may load; nothing navigates anywhere else.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                        }
                        addJavascriptInterface(bridge, "ProxBridge")
                        val page = if (target.isScreen) "vnc.html" else "terminal.html?font=$fontSize"
                        loadUrl("$ASSET_HOST/assets/console/$page")
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
            when (state) {
                ConsoleState.CONNECTING -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ConsoleState.CLOSED, ConsoleState.FAILED -> Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: stringResource(R.string.console_closed), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.padding(top = 12.dp))
                        FilledTonalButton(onClick = ::reconnect) { Text(stringResource(R.string.console_reconnect)) }
                    }
                }
                ConsoleState.CONNECTED -> Unit
            }
        }

        ExtraKeys(
            screen = target.isScreen,
            ctrl = ctrl,
            alt = alt,
            superKey = superKey,
            keyboardShown = keyboardShown,
            onCtrl = { ctrl = !ctrl; sendMods() },
            onAlt = { alt = !alt; sendMods() },
            onSuper = { superKey = !superKey; sendMods() },
            onKey = { name, seq -> if (target.isScreen) js("pcKey('$name')") else js("pcKey(${JSONObject.quote(seq)})") },
            onCtrlAltDel = { js("pcCtrlAltDel()") },
            onKeyboard = {
                keyboardShown = !keyboardShown
                if (target.isScreen) js("pcKeyboard($keyboardShown)") else js("pcFocus()")
            },
            onPaste = {
                val text = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (text.isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.paste_empty), Toast.LENGTH_SHORT).show()
                } else {
                    js("pcPaste(${JSONObject.quote(text)})")
                }
            },
        )
    }
}

/** Keys a phone keyboard doesn't have. Ctrl, Alt and Super stay pressed until the next key. */
@Composable
private fun ExtraKeys(
    screen: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    superKey: Boolean,
    keyboardShown: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onSuper: () -> Unit,
    onKey: (name: String, sequence: String) -> Unit,
    onCtrlAltDel: () -> Unit,
    onKeyboard: () -> Unit,
    onPaste: () -> Unit,
) {
    val keys = listOf(
        Triple(R.string.key_esc, "Escape", "\u001b"),
        Triple(R.string.key_tab, "Tab", "\t"),
    )
    val arrows = listOf(
        Triple("←", "Left", "\u001b[D"),
        Triple("↑", "Up", "\u001b[A"),
        Triple("↓", "Down", "\u001b[B"),
        Triple("→", "Right", "\u001b[C"),
    )
    val nav = listOf(
        Triple(R.string.key_home, "Home", "\u001b[H"),
        Triple(R.string.key_end, "End", "\u001b[F"),
        Triple(R.string.key_pgup, "PageUp", "\u001b[5~"),
        Triple(R.string.key_pgdn, "PageDown", "\u001b[6~"),
        Triple(R.string.key_del, "Delete", "\u001b[3~"),
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onKeyboard) {
                Icon(Icons.Filled.Keyboard, stringResource(R.string.console_keyboard), tint = if (keyboardShown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            keys.forEach { (label, name, seq) -> Key(stringResource(label)) { onKey(name, seq) } }
            FilterChip(selected = ctrl, onClick = onCtrl, label = { Text(stringResource(R.string.key_ctrl)) })
            FilterChip(selected = alt, onClick = onAlt, label = { Text(stringResource(R.string.key_alt)) })
            if (screen) FilterChip(selected = superKey, onClick = onSuper, label = { Text(stringResource(R.string.key_super)) })
            arrows.forEach { (label, name, seq) -> Key(label) { onKey(name, seq) } }
            if (!screen) listOf("/", "-", "|", "~").forEach { ch -> Key(ch) { onKey(ch, ch) } }
            nav.forEach { (label, name, seq) -> Key(stringResource(label)) { onKey(name, seq) } }
            if (screen) Key(stringResource(R.string.key_cad), onCtrlAltDel)
            IconButton(onClick = onPaste) { Icon(Icons.Filled.ContentPaste, stringResource(R.string.console_paste)) }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
}
