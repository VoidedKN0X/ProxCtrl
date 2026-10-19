package com.vknxworks.proxctrl

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.data.DeepLinks
import com.vknxworks.proxctrl.data.settings.ThemeChoice
import com.vknxworks.proxctrl.ui.nav.AppRoot
import com.vknxworks.proxctrl.ui.theme.ProxCtrlTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A link like proxctrl://guest/103; not again when the activity is recreated (rotation).
        if (savedInstanceState == null) DeepLinks.handle(intent?.data)
        val c = container
        setContent {
            val settings by c.settings.settings.collectAsStateWithLifecycle()
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
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinks.handle(intent.data)
    }
}
