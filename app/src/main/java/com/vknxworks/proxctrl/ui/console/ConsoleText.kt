package com.vknxworks.proxctrl.ui.console

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.ui.components.copySensitive

/**
 * A terminal's output as ordinary text, shown over the terminal: long-press to select a part and copy
 * it with Android's own selection handles, or copy or share all of it. [all] switches between what
 * was on screen and the scrollback. The text is a snapshot taken when the view opened.
 */
@Composable
internal fun ConsoleTextPanel(
    text: String,
    all: Boolean,
    onScope: (all: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val vertical = rememberScrollState()
    // Open at the end: the newest output is what's usually wanted.
    LaunchedEffect(text) { vertical.scrollTo(vertical.maxValue) }
    Surface(color = Color(0xFF0D0F12), contentColor = Color(0xFFECEEF2), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(selected = !all, onClick = { onScope(false) }, label = { Text(stringResource(R.string.console_text_screen)) })
                FilterChip(selected = all, onClick = { onScope(true) }, label = { Text(stringResource(R.string.console_text_scrollback)) })
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        // Marked sensitive: shell output can hold passwords or keys.
                        copySensitive(context, context.getString(R.string.console_text_label), text)
                        Toast.makeText(context, context.getString(R.string.console_text_copied), Toast.LENGTH_SHORT).show()
                    },
                ) { Text(stringResource(R.string.console_text_copy_all)) }
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.console_text_share))) }
                    },
                ) { Text(stringResource(R.string.console_text_share)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            }
            if (text.isBlank()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.console_text_empty), style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9AA0AA))
                }
            } else {
                Text(
                    stringResource(R.string.console_text_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9AA0AA),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
                SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(vertical)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
