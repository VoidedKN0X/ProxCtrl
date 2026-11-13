package com.vknxworks.proxctrl.ui.storage

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.theme.Gutter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val storageDateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/** Date and time in the phone's locale and time zone, from seconds since the epoch. */
internal fun storageDateTime(epochSeconds: Long): String =
    storageDateFormat.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))

/** The name for a Proxmox content type. */
@StringRes
internal fun contentLabel(type: String): Int = when (type) {
    "backup" -> R.string.storage_content_backup
    "iso" -> R.string.storage_content_iso
    "vztmpl" -> R.string.storage_content_vztmpl
    "images" -> R.string.storage_content_images
    "rootdir" -> R.string.storage_content_rootdir
    "snippets" -> R.string.storage_content_snippets
    "import" -> R.string.storage_content_import
    else -> R.string.storage_content_other
}

/** A centred spinner, for a list that is still loading. */
@Composable
internal fun LoadingBox() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** An error in place of a list that couldn't be loaded. */
@Composable
internal fun ErrorText(error: Throwable) {
    val context = LocalContext.current
    val server = context.container.sessions.active.value?.server
    Text(
        errorMessage(context, error, server?.host.orEmpty(), server?.auth),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 12.dp),
    )
}
