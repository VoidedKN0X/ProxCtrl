package com.vknxworks.proxctrl

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.vknxworks.proxctrl.data.lock.AppLock
import com.vknxworks.proxctrl.data.widgets.WidgetRefresher
import com.vknxworks.proxctrl.ui.lock.LockOverlays
import com.vknxworks.proxctrl.ui.lock.LockScreen
import com.vknxworks.proxctrl.ui.widgets.WidgetActionConfirm
import com.vknxworks.proxctrl.ui.widgets.WidgetActions
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.data.DeepLinks
import com.vknxworks.proxctrl.data.settings.ThemeChoice
import com.vknxworks.proxctrl.ui.components.ScreenPrivacy
import com.vknxworks.proxctrl.ui.nav.AppRoot
import com.vknxworks.proxctrl.ui.theme.ProxCtrlTheme
import com.vknxworks.proxctrl.ui.components.WindowPrivacy

/** A FragmentActivity (not just a ComponentActivity) because the fingerprint prompt needs one. */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Secure from the first frame (the lock is still being checked); lifted below once it's known.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        // A link like proxctrl://guest/103; not again when the activity is recreated (rotation).
        if (savedInstanceState == null) {
            DeepLinks.handle(intent?.data)
            WidgetActions.handle(intent)
        }
        val c = container
        AppLock.attach(this, c)
        // Widgets show fresh data after the app was opened, and their refresh keeps running.
        lifecycleScope.launch {
            WidgetRefresher.schedule(applicationContext)
            if (WidgetRefresher.anyWidgets(applicationContext)) WidgetRefresher.refreshSoon(applicationContext)
        }
        setContent {
            val settings by c.settings.settings.collectAsStateWithLifecycle()
            val lock by AppLock.state.collectAsStateWithLifecycle()
            // A lock or PIN screen covers the app (also "Confirm changes" and choosing a PIN).
            val covered = lock != AppLock.State.UNLOCKED || AppLock.legacy || AppLock.request != null || AppLock.confirm != null
            // "Hide in screenshots" and "Hide in recent apps" (both off by default). FLAG_SECURE is always on
            // for sign-in, consoles and the lock and PIN screens, and with the app lock on Recents always
            // shows a blank card (a preview there would show your servers without the PIN).
            val secure = settings.hideScreenshots || covered || ScreenPrivacy.forced > 0
            WindowPrivacy(this, secure, hideRecents = settings.hideRecents || c.vault.enabled || AppLock.legacy)
            val dark = when (settings.theme) {
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                ThemeChoice.LIGHT -> false
                ThemeChoice.DARK, ThemeChoice.BLACK -> true
            }
            // Keep status and navigation bar icons readable when the app theme differs from the system's.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            ProxCtrlTheme(settings.theme, settings.dynamicColor) {
                Box(Modifier.fillMaxSize()) {
                    // The app stays underneath the lock (so nothing you typed is lost), but hidden from
                    // TalkBack and other accessibility services, which could otherwise read or tap it.
                    val hidden = lock != AppLock.State.UNLOCKED
                    Box(if (hidden) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) { AppRoot() }
                    if (lock == AppLock.State.UNLOCKED) {
                        // A widget's Start / Shut down button, waiting for confirmation.
                        WidgetActionConfirm()
                        // Choosing or changing the app PIN, and "Confirm changes".
                        LockOverlays(this@MainActivity)
                    } else {
                        // Covers everything until unlocked (and while the lock setting is read at start-up).
                        LockScreen(this@MainActivity)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinks.handle(intent.data)
        WidgetActions.handle(intent)
    }
}
