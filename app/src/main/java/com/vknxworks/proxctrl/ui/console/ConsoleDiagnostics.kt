package com.vknxworks.proxctrl.ui.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vknxworks.proxctrl.R
import kotlinx.coroutines.delay
import org.json.JSONTokener

/** What the diagnostics panel shows besides the socket counters. Never holds typed text or tickets. */
class ConsoleDiag {
    @Volatile var keyboardEvents = 0
    @Volatile var lastKeyboard = "-"
    @Volatile var keyEvents = 0
    @Volatile var page = "-"
    private val errors = ArrayDeque<String>()

    @Synchronized
    fun log(message: String) {
        errors.addLast(message)
        while (errors.size > 4) errors.removeFirst()
    }

    @Synchronized
    fun errors(): List<String> = errors.toList()
}

/** evaluateJavascript hands back a JSON value; a returned string arrives quoted and escaped. */
internal fun decodeJsString(raw: String?): String =
    runCatching { JSONTokener(raw.orEmpty()).nextValue() as? String }.getOrNull() ?: raw.orEmpty()

internal fun webViewVersion(context: Context): String =
    runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull() ?: "?"

/** A small live readout of what the console is doing, to copy into a bug report. */
@Composable
internal fun DiagnosticsPanel(
    diag: ConsoleDiag,
    lines: () -> List<String>,
    refresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(emptyList<String>()) }
    val currentLines by rememberUpdatedState(lines)
    val currentRefresh by rememberUpdatedState(refresh)
    LaunchedEffect(diag) {
        while (true) {
            currentRefresh()
            delay(300)
            text = currentLines()
            delay(700)
        }
    }
    Surface(
        color = Color(0xE6101418),
        contentColor = Color(0xFFE6E8EC),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(8.dp),
    ) {
        Column(Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 2.dp)) {
            text.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("ProxCtrl console", text.joinToString("\n")))
                    Toast.makeText(context, context.getString(R.string.console_diagnostics_copied), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.console_diagnostics_copy)) }
            }
        }
    }
}
