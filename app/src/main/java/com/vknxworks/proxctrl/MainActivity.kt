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
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.vknxworks.proxctrl.data.lock.AppLock
import com.vknxworks.proxctrl.data.widgets.WidgetRefresher
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

/** A FragmentActivity (not just a ComponentActivity) because the fingerprint prompt needs one. */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recents shows a blank card, never a snapshot of a console, token or server details.
        setRecentsScreenshotEnabled(false)
        // Secure from the first frame; the setting below can lift it once it has been read.
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
        if (savedInstanceState == null) c.telemetry.appOpened()
        setContent {
            val settings by c.settings.settings.collectAsStateWithLifecycle()
            // FLAG_SECURE follows "Hide content in screenshots & recents" (on by default, and on
            // before the setting has been read), and is always on for sign-in and consoles.
            val secure = settings.hideContent || ScreenPrivacy.forced > 0
            LaunchedEffect(secure) {
                if (secure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
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
            val lock by AppLock.state.collectAsStateWithLifecycle()
            ProxCtrlTheme(settings.theme, settings.dynamicColor) {
                Box(Modifier.fillMaxSize()) {
                    AppRoot()
                    if (lock == AppLock.State.UNLOCKED) {
                        // A widget's Start / Shut down button, waiting for confirmation.
                        WidgetActionConfirm()
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
