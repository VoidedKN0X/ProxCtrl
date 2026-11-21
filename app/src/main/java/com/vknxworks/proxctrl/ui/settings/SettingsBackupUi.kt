package com.vknxworks.proxctrl.ui.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.BuildConfig
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.notify.NotifyEvent
import com.vknxworks.proxctrl.data.notify.NotifySettings
import com.vknxworks.proxctrl.data.settings.AppSettings
import com.vknxworks.proxctrl.data.settings.GuestSort
import com.vknxworks.proxctrl.data.settings.SettingsBackup
import com.vknxworks.proxctrl.data.settings.SettingsSnapshot
import com.vknxworks.proxctrl.ui.components.CtrlDialog
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.ShieldScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

private fun snapshot(app: AppSettings, notify: NotifySettings) = SettingsSnapshot(
    guestSort = app.guestSort.name,
    guestSortDescending = app.guestSortDescending,
    guestCompact = app.guestCompact,
    diskWarnPercent = app.diskWarnPercent,
    memWarnPercent = app.memWarnPercent,
    backupMaxAgeDays = app.backupMaxAgeDays,
    hideScreenshots = app.hideScreenshots,
    hideRecents = app.hideRecents,
    notifyIntervalMinutes = notify.intervalMinutes,
    notifyDisabled = notify.disabled.map { it.name }.toSet(),
    notifyCpuPercent = notify.cpuPercent,
    notifySustainMinutes = notify.sustainMinutes,
)

@StringRes
private fun fieldLabel(field: SettingsBackup.Field): Int = when (field) {
    SettingsBackup.Field.GUEST_SORT -> R.string.backup_field_sort
    SettingsBackup.Field.GUEST_SORT_DESCENDING -> R.string.backup_field_sort_direction
    SettingsBackup.Field.GUEST_COMPACT -> R.string.view_compact
    SettingsBackup.Field.DISK_WARN -> R.string.disk_warn_title
    SettingsBackup.Field.MEM_WARN -> R.string.mem_warn_title
    SettingsBackup.Field.BACKUP_AGE -> R.string.backup_age_title
    SettingsBackup.Field.HIDE_SCREENSHOTS -> R.string.hide_screenshots
    SettingsBackup.Field.HIDE_RECENTS -> R.string.hide_recents
    SettingsBackup.Field.NOTIFY_INTERVAL -> R.string.backup_field_interval
    SettingsBackup.Field.NOTIFY_EVENTS -> R.string.backup_field_events
    SettingsBackup.Field.NOTIFY_CPU -> R.string.backup_field_cpu
    SettingsBackup.Field.NOTIFY_SUSTAIN -> R.string.backup_field_sustain
}

/** Reads at most [SettingsBackup.MAX_BYTES] of the picked file; null when it's bigger or unreadable. */
private fun readSmall(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readNBytes(SettingsBackup.MAX_BYTES + 1)
        if (bytes.size > SettingsBackup.MAX_BYTES) null else String(bytes, Charsets.UTF_8)
    }
}.getOrNull()

/**
 * "Back up and restore" in Settings: writes the app's own settings to a JSON file you choose, and
 * reads such a file back after showing what it would change. Logins, certificates and the app lock
 * are never part of the file (see [SettingsSnapshot]).
 */
@Composable
fun SettingsBackupGroup() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    /** The settings as they'd be after the import, waiting for the confirmation. */
    var pending by remember { mutableStateOf<Pair<SettingsSnapshot, List<SettingsBackup.Field>>?>(null) }

    fun toast(@StringRes message: Int) = Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()

    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val text = SettingsBackup.toJson(snapshot(c.settings.settings.value, c.notifySettings.settings.value), Instant.now().toString(), BuildConfig.VERSION_NAME)
                val ok = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null }.getOrDefault(false)
                }
                toast(if (ok) R.string.backup_export_done else R.string.backup_export_failed)
            }
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) { readSmall(context, uri) }
                if (text == null) {
                    toast(R.string.backup_import_unreadable)
                    return@launch
                }
                val current = snapshot(c.settings.settings.value, c.notifySettings.settings.value)
                val merged = try {
                    SettingsBackup.merge(
                        text = text,
                        current = current,
                        sorts = GuestSort.entries.map { it.name }.toSet(),
                        events = NotifyEvent.entries.map { it.name }.toSet(),
                        intervals = NotifySettings.INTERVALS,
                        cpuChoices = NotifySettings.CPU,
                        sustainChoices = NotifySettings.SUSTAIN,
                    )
                } catch (e: SettingsBackup.NotASettingsFile) {
                    toast(R.string.backup_import_not_ours)
                    return@launch
                }
                val changes = SettingsBackup.changes(current, merged)
                if (changes.isEmpty()) toast(R.string.backup_import_same) else pending = merged to changes
            }
        }
    }

    Group(stringResource(R.string.backup_group), footer = stringResource(R.string.backup_footer)) {
        GroupRow(
            title = stringResource(R.string.backup_export),
            subtitle = stringResource(R.string.backup_export_subtitle),
            icon = Icons.Outlined.FileUpload,
            onClick = { exportFile.launch("proxctrl-settings-${LocalDate.now()}.json") },
        )
        GroupRow(
            title = stringResource(R.string.backup_import),
            subtitle = stringResource(R.string.backup_import_subtitle),
            icon = Icons.Outlined.FileDownload,
            onClick = { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        )
    }

    pending?.let { (merged, changes) ->
        ShieldScreen(sensitiveData = false)
        CtrlDialog(
            onDismissRequest = { pending = null },
            icon = { Icon(Icons.Outlined.FileDownload, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.backup_import_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.backup_import_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    changes.forEach { field ->
                        Text("•  " + stringResource(fieldLabel(field)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    }
                    Text(
                        stringResource(R.string.backup_import_keeps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    c.appScope.launch {
                        c.settings.update { s ->
                            s.copy(
                                guestSort = GuestSort.entries.firstOrNull { it.name == merged.guestSort } ?: s.guestSort,
                                guestSortDescending = merged.guestSortDescending,
                                guestCompact = merged.guestCompact,
                                diskWarnPercent = merged.diskWarnPercent,
                                memWarnPercent = merged.memWarnPercent,
                                backupMaxAgeDays = merged.backupMaxAgeDays,
                                // Never switched off by an import, whatever was on when the file was read.
                                hideScreenshots = s.hideScreenshots || merged.hideScreenshots,
                                hideRecents = s.hideRecents || merged.hideRecents,
                            )
                        }
                        c.notifySettings.update { n ->
                            n.copy(
                                intervalMinutes = merged.notifyIntervalMinutes,
                                disabled = NotifyEvent.entries.filter { it.name in merged.notifyDisabled }.toSet(),
                                cpuPercent = merged.notifyCpuPercent,
                                sustainMinutes = merged.notifySustainMinutes,
                            )
                        }
                        withContext(Dispatchers.Main) { toast(R.string.backup_import_done) }
                    }
                }) { Text(stringResource(R.string.backup_import_confirm)) }
            },
            dismissButton = { TextButton({ pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
