package com.vknxworks.proxctrl.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.util.WeakHashMap

/**
 * True while the app is covered by the lock screen, or its activity is closing. Dialogs, bottom sheets
 * and menus are windows of their own, drawn above everything in the activity, so while this is true they
 * aren't shown: one left open would sit on top of the lock screen (and still take taps), and a new window
 * can't be added to an activity that is closing.
 */
val LocalAppCovered = compositionLocalOf { false }

/**
 * Screens that always get FLAG_SECURE, whatever "Hide in screenshots" says: server sign-in
 * (token secret, password, two-factor code) and consoles (a root shell and whatever it prints).
 * A count rather than a flag, so two such screens overlapping during a transition can't clear it early.
 */
object ScreenPrivacy {
    var forced by mutableIntStateOf(0)
        private set

    internal fun acquire() { forced++ }
    internal fun release() { forced = (forced - 1).coerceAtLeast(0) }
}

/** Keeps FLAG_SECURE on for as long as the calling screen is shown. */
@Composable
fun SecureScreen() {
    DisposableEffect(Unit) {
        ScreenPrivacy.acquire()
        onDispose { ScreenPrivacy.release() }
    }
}

/**
 * Applies "Hide in screenshots" ([secure]: FLAG_SECURE, which also blanks the recent-apps preview) and
 * "Hide in recent apps" ([hideRecents]: a blank card in Recents, screenshots still work) to [activity]'s
 * window, and again whenever they change, so a switch works at once in every open screen.
 */
@Composable
fun WindowPrivacy(activity: Activity, secure: Boolean, hideRecents: Boolean) {
    LaunchedEffect(secure) {
        if (secure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    LaunchedEffect(hideRecents) { activity.setRecentsScreenshotEnabled(!hideRecents) }
}

/**
 * Copies [text] marked as sensitive, so Android hides it from the clipboard preview and keyboards
 * don't keep it in their clipboard history.
 */
fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/**
 * While a PIN, lock, sign-in or destructive confirmation screen is showing:
 * - other apps' floating windows (chat heads, "draw over other apps" overlays) are hidden, so nothing can
 *   cover or imitate the screen to trick a tap or capture the PIN (Android 12+);
 * - with [sensitiveData], the screen's contents are only shared with real accessibility tools such as
 *   screen readers, not with other apps that use the accessibility API to read the screen (Android 14+).
 * Counted per window, so screens that overlap during a transition can't turn it off early; everything
 * goes back to normal when the last one closes. Other screens of the app aren't affected.
 */
@Composable
fun ShieldScreen(sensitiveData: Boolean = false) {
    val view = LocalView.current
    DisposableEffect(view, sensitiveData) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.shieldActivity()?.window
        val root = view.rootView
        if (window != null) Shields.hideOverlays(window, true)
        if (sensitiveData) Shields.sensitive(root, true)
        onDispose {
            if (window != null) Shields.hideOverlays(window, false)
            if (sensitiveData) Shields.sensitive(root, false)
        }
    }
}

private object Shields {
    private val overlayCounts = WeakHashMap<Window, Int>()
    private val sensitiveCounts = WeakHashMap<View, Int>()

    fun hideOverlays(window: Window, on: Boolean) {
        val n = (overlayCounts[window] ?: 0) + if (on) 1 else -1
        overlayCounts[window] = n.coerceAtLeast(0)
        if ((on && n == 1) || (!on && n <= 0)) runCatching { window.setHideOverlayWindows(on) }
    }

    fun sensitive(view: View, on: Boolean) {
        val n = (sensitiveCounts[view] ?: 0) + if (on) 1 else -1
        sensitiveCounts[view] = n.coerceAtLeast(0)
        if ((on && n == 1) || (!on && n <= 0)) runCatching {
            view.setAccessibilityDataSensitive(
                if (on) View.ACCESSIBILITY_DATA_SENSITIVE_YES else View.ACCESSIBILITY_DATA_SENSITIVE_AUTO,
            )
        }
    }
}

private tailrec fun Context.shieldActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.shieldActivity()
    else -> null
}
