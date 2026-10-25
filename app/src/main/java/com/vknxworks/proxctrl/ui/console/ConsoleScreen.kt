package com.vknxworks.proxctrl.ui.console

import androidx.activity.enableEdgeToEdge
import com.vknxworks.proxctrl.ui.theme.isSystemDark
import com.vknxworks.proxctrl.ui.components.CtrlSecondaryButton
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import com.vknxworks.proxctrl.BuildConfig
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.console.ConsoleSession
import com.vknxworks.proxctrl.data.console.ConsoleTarget
import com.vknxworks.proxctrl.data.server.AuthMethod
import com.vknxworks.proxctrl.ui.components.SecureScreen
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference

private const val ASSET_HOST = "https://appassets.androidplatform.net"

private enum class ConsoleState { CONNECTING, CONNECTED, CLOSED, FAILED }

/** What the hidden keyboard field always holds, so Backspace always has something to delete. */
private const val KEYBOARD_SENTINEL = "                                "

/** Escape sequences a terminal expects for keys without a character. */
private val TERMINAL_KEYS = mapOf(
    "Escape" to "\u001b", "Tab" to "\t",
    "Up" to "\u001b[A", "Down" to "\u001b[B", "Right" to "\u001b[C", "Left" to "\u001b[D",
    "Home" to "\u001b[H", "End" to "\u001b[F", "PageUp" to "\u001b[5~", "PageDown" to "\u001b[6~", "Delete" to "\u001b[3~",
)

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
    fun setError(message: String?) { error = message }
    var attempt by remember { mutableIntStateOf(0) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var superKey by remember { mutableStateOf(false) }
    var fontSize by remember { mutableIntStateOf(13) }
    var landscape by remember { mutableStateOf(false) }
    var showLoginNote by remember {
        mutableStateOf(target is ConsoleTarget.NodeShell && !(server?.auth == AuthMethod.TICKET && server.username == "root@pam"))
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var input by remember { mutableStateOf(TextFieldValue(KEYBOARD_SENTINEL, TextRange(KEYBOARD_SENTINEL.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    // Read from WebView's bridge thread as well as the main thread.
    val sessionRef = remember { AtomicReference<ConsoleSession?>(null) }
    var showDiag by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val diag = remember { ConsoleDiag() }
    var consoleBounds by remember { mutableStateOf<List<Int>?>(null) }

    fun js(code: String) {
        main.post { webView?.evaluateJavascript(code, null) }
    }

    // A shell shows whatever the server prints (keys, passwords, configs): never in screenshots or Recents.
    SecureScreen()

    // Full screen while the console is open; restore bars and rotation when leaving.
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            sessionRef.getAndSet(null)?.close()
            // Remote debugging (from the diagnostics panel) never outlives the console.
            runCatching { WebView.setWebContentsDebuggingEnabled(false) }
        }
    }

    // Read-only mode switched on while the console is open: close it. New consoles can't start
    // either, because the API client refuses the proxy request in read-only mode.
    LaunchedEffect(server?.readOnly) {
        if (server?.readOnly == true) {
            sessionRef.getAndSet(null)?.close()
            state = ConsoleState.FAILED
            error = context.getString(R.string.error_read_only)
        }
    }

    /**
     * Events from one session's socket. A session replaced by a reconnect may still report closing;
     * that must not reach the new page, or it would mark the new connection closed and drop all typing.
     */
    fun listenerFor(owner: ConsoleSession) = object : ConsoleSession.Listener {
        private fun current() = sessionRef.get() === owner
        override fun onOpen() { if (current()) js("pcOpen()") }
        override fun onBytes(bytes: ByteArray) {
            if (current()) js("pcRecv('${Base64.encodeToString(bytes, Base64.NO_WRAP)}')")
        }
        override fun onClosed(code: Int, reason: String) {
            if (!current()) return
            js("pcClosed($code, ${JSONObject.quote(reason)})")
            main.post { if (state != ConsoleState.FAILED) state = ConsoleState.CLOSED }
        }
        override fun onFailure(error: Throwable) {
            if (!current()) return
            val t = error
            diag.log("socket: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
            js("pcError('socket')")
            main.post {
                state = ConsoleState.FAILED
                setError(errorMessage(context, t, server?.host.orEmpty(), server?.auth))
            }
        }
    }

    fun connect() {
        val client = active?.client ?: return
        val s = ConsoleSession(client, target)
        sessionRef.getAndSet(s)?.close()
        state = ConsoleState.CONNECTING
        error = null
        scope.launch {
            try {
                s.connect(listenerFor(s))
            } catch (e: Exception) {
                if (sessionRef.get() !== s) return@launch
                diag.log("proxy: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}")
                state = ConsoleState.FAILED
                error = errorMessage(context, e, server?.host.orEmpty(), server?.auth).let { m ->
                    if (e is PveError.Server && (e.detail?.contains("not running", true) == true)) context.getString(R.string.console_not_running) else m
                }
            }
        }
    }

    /** What the console pages can call. Every method only acts on this console's own session. */
    val bridge = remember {
        ConsoleBridge(
            main = main,
            session = { sessionRef.get() },
            onPageReady = { connect() },
            onConnected = { state = ConsoleState.CONNECTED },
            onDisconnected = { if (state != ConsoleState.FAILED) state = ConsoleState.CLOSED },
            onFailed = { message ->
                diag.log("page: ${message.take(120)}")
                state = ConsoleState.FAILED
                error = context.getString(R.string.console_failed, message)
            },
            onModsReleased = { ctrl = false; alt = false; superKey = false },
            onLog = diag::log,
        )
    }

    fun reconnect() {
        attempt++
        state = ConsoleState.CONNECTING
        webView?.reload()
    }

    fun sendMods() {
        if (target.isScreen) js("pcSetMods($ctrl, $alt, $superKey)") else js("pcSetMods($ctrl, $alt)")
    }

    fun showKeyboard() {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    fun hideKeyboard() {
        keyboard?.hide()
    }

    // Terminals are for typing: bring the keyboard up as soon as one is connected.
    LaunchedEffect(state) {
        if (state == ConsoleState.CONNECTED && !target.isScreen) showKeyboard()
    }

    /**
     * The phone keyboard types into this hidden field instead of the console page (inside the app the
     * page doesn't reliably get keyboard input). The field always holds a fixed filler text; whatever
     * the keyboard adds to it is sent as typing, and whatever it removes is sent as Backspace.
     */
    fun onInput(value: TextFieldValue) {
        val old = KEYBOARD_SENTINEL
        val new = value.text
        if (value.composition != null && new.startsWith(old)) {
            // Still composing (some keyboards do even for password fields): wait for the committed text.
            input = value
            return
        }
        val common = old.commonPrefixWith(new).length
        val removed = old.length - common
        val added = new.substring(common)
        diag.keyboardEvents++
        diag.lastKeyboard = "+${added.length} -$removed"
        if (removed > 0) js("pcBackspace($removed)")
        if (added.isNotEmpty()) js("pcInput(${JSONObject.quote(added)})")
        input = TextFieldValue(KEYBOARD_SENTINEL, TextRange(KEYBOARD_SENTINEL.length))
    }

    /** Keys from a hardware keyboard, or ones the phone keyboard sends as key presses. */
    fun onHardwareKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        diag.keyEvents++
        val name = when (event.key) {
            Key.Enter, Key.NumPadEnter -> "Enter"
            Key.Backspace -> "Backspace"
            Key.Escape -> "Escape"
            Key.Tab -> "Tab"
            Key.DirectionUp -> "Up"
            Key.DirectionDown -> "Down"
            Key.DirectionLeft -> "Left"
            Key.DirectionRight -> "Right"
            Key.MoveHome -> "Home"
            Key.MoveEnd -> "End"
            Key.PageUp -> "PageUp"
            Key.PageDown -> "PageDown"
            Key.Delete -> "Delete"
            else -> null
        } ?: return false
        if (target.isScreen) {
            js("pcKey('$name')")
        } else {
            val seq = when (name) {
                "Enter" -> "\r"
                "Backspace" -> "\u007f"
                else -> TERMINAL_KEYS[name] ?: return false
            }
            js("pcKey(${JSONObject.quote(seq)})")
        }
        return true
    }

    /*
     * The console page runs in a WebView added straight to the activity's window, on top of the
     * app, instead of inside the Compose layout. Drawn inside Compose, the page stopped being
     * redrawn on some phones: typing and output reached the server and the page, but the screen
     * stayed frozen on its first frame.
     */
    val consoleHost = remember(activity) { activity?.findViewById<ViewGroup>(android.R.id.content) }
    DisposableEffect(consoleHost) {
        val host = consoleHost
        val wv = host?.let { h ->
            val ctx = h.context
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                .build()
            WebView(ctx).apply {
                setBackgroundColor(Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setGeolocationEnabled(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.setSupportZoom(false)
                settings.mediaPlaybackRequiresUserGesture = true
                webViewClient = object : WebViewClient() {
                    // Only the bundled files load. Anything else (another host, a link in terminal
                    // output) gets an empty 403 instead of going out to the network.
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)
                            ?: WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

                    // Only the bundled pages may load; nothing navigates anywhere else.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            diag.log("js: ${message.message().take(200)} @ ${message.sourceId().substringAfterLast('/')}:${message.lineNumber()}")
                        }
                        return true
                    }
                }
                addJavascriptInterface(bridge, "ProxBridge")
                // Keyboard input goes through the app's own field, never into the page.
                isFocusable = false
                isFocusableInTouchMode = false
                if (!target.isScreen) {
                    // A tap on a terminal brings up the keyboard; drags still scroll it.
                    val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
                    var downX = 0f
                    var downY = 0f
                    setOnTouchListener { _, e ->
                        when (e.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                            android.view.MotionEvent.ACTION_UP ->
                                if (kotlin.math.abs(e.x - downX) < slop && kotlin.math.abs(e.y - downY) < slop) main.post { showKeyboard() }
                        }
                        false
                    }
                }
                val page = if (target.isScreen) "vnc.html" else "terminal.html?font=$fontSize"
                loadUrl("$ASSET_HOST/assets/console/$page")
                webView = this
            }
        }
        if (host != null && wv != null) host.addView(wv, FrameLayout.LayoutParams(0, 0))
        onDispose {
            if (wv != null) {
                host?.removeView(wv)
                wv.destroy()
            }
            webView = null
        }
    }

    // Keep the page exactly over its spot in the layout (it moves with the keyboard and banners).
    LaunchedEffect(consoleBounds, webView) {
        val wv = webView ?: return@LaunchedEffect
        val host = consoleHost ?: return@LaunchedEffect
        val (x, y, w, h) = consoleBounds ?: return@LaunchedEffect
        val origin = IntArray(2).also(host::getLocationInWindow)
        wv.translationX = (x - origin[0]).toFloat()
        wv.translationY = (y - origin[1]).toFloat()
        val lp = wv.layoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            wv.layoutParams = lp
        }
    }

    // The console is dark in light mode too, so its status and navigation bar icons stay light while it's open.
    DisposableEffect(activity) {
        val ca = activity as? androidx.activity.ComponentActivity
        ca?.enableEdgeToEdge(
            statusBarStyle = com.vknxworks.proxctrl.ui.theme.signalBarStyle(true),
            navigationBarStyle = com.vknxworks.proxctrl.ui.theme.signalBarStyle(true),
        )
        onDispose {
            if (ca != null) {
                val dark = ca.isSystemDark()
                ca.enableEdgeToEdge(
                    statusBarStyle = com.vknxworks.proxctrl.ui.theme.signalBarStyle(dark),
                    navigationBarStyle = com.vknxworks.proxctrl.ui.theme.signalBarStyle(dark),
                )
            }
        }
    }

    // Only show the page once it's connected, so the progress and error cards (drawn by the app,
    // underneath the page) stay visible until then.
    // The page is a view on top of the whole window, so it's also hidden while the lock screen or a PIN
    // screen covers the app: a console must never show (or take typing) over the lock.
    val covered = com.vknxworks.proxctrl.ui.components.LocalAppCovered.current
    LaunchedEffect(state, webView, covered) {
        webView?.visibility = if (state == ConsoleState.CONNECTED && !covered) View.VISIBLE else View.INVISIBLE
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        // Compact bar: back, title, and tools.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), tint = androidx.compose.ui.graphics.Color.White) }
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
                    Icon(Icons.Outlined.TextDecrease, stringResource(R.string.console_font_smaller), tint = androidx.compose.ui.graphics.Color.White)
                }
                IconButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(28); js("pcFontSize($fontSize)") }) {
                    Icon(Icons.Outlined.TextIncrease, stringResource(R.string.console_font_bigger), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            IconButton(onClick = {
                showDiag = !showDiag
                // Debug builds only: lets the console page be inspected from a computer (chrome://inspect)
                // while the panel is open. Never in a release, where it would expose the open shell to
                // anything connected over USB debugging.
                if (BuildConfig.DEBUG) runCatching { WebView.setWebContentsDebuggingEnabled(showDiag) }
            }) {
                Icon(
                    Icons.Outlined.BugReport,
                    stringResource(R.string.console_diagnostics),
                    tint = if (showDiag) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White,
                )
            }
            IconButton(onClick = {
                landscape = !landscape
                activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }) { Icon(Icons.Outlined.ScreenRotation, stringResource(R.string.console_rotate), tint = androidx.compose.ui.graphics.Color.White) }
        }

        if (showLoginNote) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.console_token_login), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showLoginNote = false }) { Text(stringResource(R.string.console_dismiss)) }
                }
            }
        }

        if (showDiag) {
            DiagnosticsPanel(
                diag = diag,
                lines = {
                    val st = sessionRef.get()?.stats
                    buildList {
                        add("state $state · page ready ${bridge.pageReadyCalls.get()}× · webview ${webViewVersion(context)}")
                        add(
                            if (st == null) "socket: no session"
                            else "socket: proxy ${if (st.proxyStarted) "ok" else "-"} · open ${st.socketOpen}" + (st.closeInfo?.let { " · $it" } ?: ""),
                        )
                        if (st != null) {
                            add("in ${st.framesIn.get()} msgs/${st.bytesIn.get()} B · out ${st.framesOut.get()} msgs/${st.bytesOut.get()} B · dropped ${st.dropped.get()} · last ${st.lastOut}")
                        }
                        add("page sends ${bridge.sendCalls.get()} · without session ${bridge.sendsWithoutSession.get()}")
                        add("keyboard field focused $fieldFocused · ime $imeVisible · text ${diag.keyboardEvents} (${diag.lastKeyboard}) · keys ${diag.keyEvents}")
                        add("page ${diag.page}")
                        diag.errors().forEach { add("! $it") }
                    }
                },
                refresh = {
                    main.post { webView?.evaluateJavascript("typeof pcDiag === 'function' ? pcDiag() : 'no pcDiag'") { diag.page = decodeJsString(it) } }
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Where the console goes. The WebView itself is laid over this spot in the window (see below).
            Box(
                Modifier.fillMaxSize().onGloballyPositioned { coords ->
                    val pos = coords.positionInWindow()
                    consoleBounds = listOf(pos.x.toInt(), pos.y.toInt(), coords.size.width, coords.size.height)
                },
            )
            BasicTextField(
                value = input,
                onValueChange = ::onInput,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.None,
                ),
                singleLine = false,
                textStyle = TextStyle(color = androidx.compose.ui.graphics.Color.Transparent),
                cursorBrush = SolidColor(androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(focus)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    .onPreviewKeyEvent(::onHardwareKey),
            )
            when (state) {
                ConsoleState.CONNECTING -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ConsoleState.CLOSED, ConsoleState.FAILED -> Surface(
                    shape = MaterialTheme.shapes.large,
                    border = androidx.compose.foundation.BorderStroke(1.dp, com.vknxworks.proxctrl.ui.theme.CtrlColors.Outline),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: stringResource(R.string.console_closed), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.padding(top = 12.dp))
                        CtrlSecondaryButton(onClick = ::reconnect) { Text(stringResource(R.string.console_reconnect)) }
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
            keyboardShown = imeVisible,
            onCtrl = { ctrl = !ctrl; sendMods() },
            onAlt = { alt = !alt; sendMods() },
            onSuper = { superKey = !superKey; sendMods() },
            onKey = { name, seq -> if (target.isScreen) js("pcKey('$name')") else js("pcKey(${JSONObject.quote(seq)})") },
            onCtrlAltDel = { js("pcCtrlAltDel()") },
            onKeyboard = { if (imeVisible) hideKeyboard() else showKeyboard() },
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
                Icon(Icons.Outlined.Keyboard, stringResource(R.string.console_keyboard), tint = if (keyboardShown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            keys.forEach { (label, name, seq) -> Key(stringResource(label)) { onKey(name, seq) } }
            FilterChip(selected = ctrl, onClick = onCtrl, label = { Text(stringResource(R.string.key_ctrl)) })
            FilterChip(selected = alt, onClick = onAlt, label = { Text(stringResource(R.string.key_alt)) })
            if (screen) FilterChip(selected = superKey, onClick = onSuper, label = { Text(stringResource(R.string.key_super)) })
            arrows.forEach { (label, name, seq) -> Key(label) { onKey(name, seq) } }
            if (!screen) listOf("/", "-", "|", "~").forEach { ch -> Key(ch) { onKey(ch, ch) } }
            nav.forEach { (label, name, seq) -> Key(stringResource(label)) { onKey(name, seq) } }
            if (screen) Key(stringResource(R.string.key_cad), onCtrlAltDel)
            IconButton(onClick = onPaste) { Icon(Icons.Outlined.ContentPaste, stringResource(R.string.console_paste)) }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    CtrlSecondaryButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
}
