package com.vknxworks.proxctrl.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Screens that always get FLAG_SECURE, whatever the "Hide content" setting says: server sign-in
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
 * Copies [text] marked as sensitive, so Android hides it from the clipboard preview and keyboards
 * don't keep it in their clipboard history.
 */
fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}
