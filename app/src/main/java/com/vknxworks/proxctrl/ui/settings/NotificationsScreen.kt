package com.vknxworks.proxctrl.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.notify.CheckStore
import com.vknxworks.proxctrl.data.notify.NotifyEvent
import com.vknxworks.proxctrl.data.notify.NotifySettings
import com.vknxworks.proxctrl.data.notify.NotifyWorker
import com.vknxworks.proxctrl.data.notify.Push
import com.vknxworks.proxctrl.data.notify.PushStatus
import com.vknxworks.proxctrl.data.notify.UnifiedPush
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupChoice
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.GroupSwitch
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.copySensitive
import com.vknxworks.proxctrl.ui.components.relativeTime
import com.vknxworks.proxctrl.ui.components.rememberNotificationAccess
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.coroutines.launch

/** Background checks, push from Proxmox, per-event switches, thresholds and battery advice. */
@Composable
fun NotificationsScreen() {
    val context = LocalContext.current
    val c = context.container
    val settings by c.notifySettings.settings.collectAsStateWithLifecycle()
    val servers by c.sessions.servers.collectAsStateWithLifecycle()
    val lastCheck by CheckStore.lastCheck.collectAsStateWithLifecycle()
    val pushStatus by Push.status.collectAsStateWithLifecycle()
    // Re-checked after the permission dialog and whenever the screen comes back (from Android's settings).
    val access = rememberNotificationAccess()
    val canPost = access.canPost
    var batteryVersion by remember { mutableIntStateOf(0) }
    val unrestricted = remember(batteryVersion) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val ntfyInstalled = remember(batteryVersion) { UnifiedPush.isNtfyInstalled(context) }
    val ntfyUnrestricted = remember(batteryVersion) { UnifiedPush.isNtfyUnrestricted(context) }

    // Re-check permission, battery and push state when coming back from system settings or ntfy.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            batteryVersion++
            servers.forEach { CheckStore.read(context, it.id) }
            Push.load(context, c.notifySettings.current().push)
        }
    }

    /** Turning a notification feature on asks for the permission (or opens Android's settings) when notifications are blocked. */
    fun ensurePermission() {
        if (!canPost) access.fix()
    }
    fun update(transform: (NotifySettings) -> NotifySettings) {
        c.appScope.launch { c.notifySettings.update(transform) }
    }
    fun copy(text: String) {
        // The push address and setup commands hold the secret ntfy topic: keep them out of clipboard previews.
        copySensitive(context, "ProxCtrl", text)
        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }
    fun startPush() {
        Push.register(context)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.notifications))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.notif_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
            )

            if (!canPost && (settings.polling || settings.push)) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.notif_permission_off), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { access.fix() }) { Text(stringResource(R.string.notif_permission_allow)) }
                    }
                }
            }

            // --- Background checks ---
            Group(stringResource(R.string.group_polling), footer = stringResource(R.string.polling_footer)) {
                GroupSwitch(
                    stringResource(R.string.polling_title),
                    stringResource(R.string.polling_subtitle),
                    // Shown off while notifications are blocked: the checks can't tell you anything then.
                    checked = settings.polling && canPost,
                    icon = Icons.Filled.Schedule,
                ) { on ->
                    if (on) ensurePermission()
                    update { it.copy(polling = on) }
                }
                if (settings.polling && canPost) {
                    GroupChoice(
                        stringResource(R.string.polling_interval),
                        NotifySettings.INTERVALS.map { it to pluralStringResource(R.plurals.polling_interval_value, it, it) },
                        settings.intervalMinutes,
                        icon = Icons.Filled.Timer,
                    ) { m -> update { it.copy(intervalMinutes = m) } }
                }
                servers.forEach { server ->
                    val st = lastCheck[server.id]
                    val subtitle = when {
                        st == null || st.lastCheckAt == 0L -> stringResource(R.string.last_check_never)
                        st.lastResult == "ok" -> stringResource(R.string.last_check, relativeTime(st.lastCheckAt * 1000))
                        else -> stringResource(R.string.last_check_failed, relativeTime(st.lastCheckAt * 1000))
                    }
                    GroupRow(server.name, subtitle)
                }
                GroupRow(
                    stringResource(R.string.check_now),
                    icon = Icons.Filled.Refresh,
                    onClick = {
                        access.askIfPossible()
                        NotifyWorker.runNow(context)
                        Toast.makeText(context, context.getString(R.string.check_started), Toast.LENGTH_SHORT).show()
                    },
                )
            }

            // --- Push ---
            Group(stringResource(R.string.group_push), footer = if (settings.push) stringResource(R.string.push_dedupe_note) else null) {
                GroupSwitch(
                    stringResource(R.string.push_title),
                    stringResource(R.string.push_subtitle),
                    checked = settings.push && canPost,
                    icon = Icons.Filled.Campaign,
                ) { on ->
                    update { it.copy(push = on) }
                    if (on) {
                        ensurePermission()
                        startPush()
                    } else {
                        Push.unregister(context)
                    }
                }
                if (!ntfyInstalled) {
                    PushNote(stringResource(R.string.push_no_ntfy)) {
                        FilledTonalButton(onClick = {
                            runCatching {
                                com.vknxworks.proxctrl.data.lock.AppLock.expectReturn()
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UnifiedPush.NTFY_DOWNLOAD)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text(stringResource(R.string.push_install_ntfy)) }
                    }
                }
                if (settings.push && canPost && ntfyInstalled) {
                    when (val st = pushStatus) {
                        PushStatus.NoNtfy, PushStatus.Off, PushStatus.Registering -> PushNote(stringResource(R.string.push_registering)) {
                            TextButton(onClick = ::startPush) { Text(stringResource(R.string.retry)) }
                        }
                        is PushStatus.Failed -> PushNote(stringResource(R.string.push_failed, st.reason)) {
                            TextButton(onClick = ::startPush) { Text(stringResource(R.string.retry)) }
                        }
                        is PushStatus.Unavailable -> PushNote(stringResource(R.string.push_unavailable)) {}
                        is PushStatus.Registered -> {
                            GroupRow(
                                stringResource(R.string.push_endpoint),
                                st.endpoint,
                                icon = Icons.Filled.NotificationsActive,
                                trailing = { Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy)) },
                                onClick = { copy(st.endpoint) },
                            )
                            Text(
                                stringResource(R.string.push_endpoint_footer),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            SetupCommands(Push.setupCommands(st.endpoint), onCopy = ::copy)
                        }
                    }
                }
            }

            // --- Events ---
            Group(stringResource(R.string.group_events)) {
                NotifyEvent.entries.forEach { e ->
                    val coveredByPush = settings.push && (e == NotifyEvent.BACKUP_FAILED || e == NotifyEvent.UPDATES)
                    GroupSwitch(
                        stringResource(e.label),
                        if (coveredByPush) stringResource(e.summary) + "\n" + stringResource(R.string.ev_sent_by_proxmox) else stringResource(e.summary),
                        checked = settings.on(e) && canPost,
                    ) { on ->
                        if (on) ensurePermission()
                        update { s -> s.copy(disabled = if (on) s.disabled - e else s.disabled + e) }
                    }
                }
            }

            // --- Thresholds ---
            Group(stringResource(R.string.group_thresholds), footer = stringResource(R.string.thresholds_footer)) {
                GroupChoice(
                    stringResource(R.string.cpu_threshold),
                    NotifySettings.CPU.map { it to stringResource(R.string.cpu_threshold_value, it) },
                    settings.cpuPercent,
                    icon = Icons.Filled.Speed,
                ) { v -> update { it.copy(cpuPercent = v) } }
                GroupChoice(
                    stringResource(R.string.sustain_title),
                    NotifySettings.SUSTAIN.map { it to pluralStringResource(R.plurals.sustain_value, it, it) },
                    settings.sustainMinutes,
                    icon = Icons.Filled.Timer,
                ) { v -> update { it.copy(sustainMinutes = v) } }
            }

            // --- Battery ---
            Group(stringResource(R.string.group_battery), footer = stringResource(R.string.battery_footer)) {
                GroupRow(
                    stringResource(if (unrestricted) R.string.battery_unrestricted else R.string.battery_restricted),
                    icon = if (unrestricted) Icons.Filled.BatteryFull else Icons.Filled.BatteryAlert,
                    iconTint = if (unrestricted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                if (settings.push && ntfyInstalled && !ntfyUnrestricted) {
                    GroupRow(
                        stringResource(R.string.battery_ntfy_restricted),
                        stringResource(R.string.battery_ntfy_restricted_body),
                        icon = Icons.Filled.BatteryAlert,
                        iconTint = MaterialTheme.colorScheme.error,
                        showChevron = true,
                        onClick = {
                            val ntfyInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${UnifiedPush.NTFY}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            com.vknxworks.proxctrl.data.lock.AppLock.expectReturn()
                            runCatching { context.startActivity(ntfyInfo) }
                        },
                    )
                }
                GroupRow(
                    stringResource(R.string.battery_open),
                    showChevron = true,
                    onClick = {
                        // App info is where ColorOS keeps "Allow background activity"; fall back to the battery list.
                        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        com.vknxworks.proxctrl.data.lock.AppLock.expectReturn()
                        runCatching { context.startActivity(appInfo) }.onFailure {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    },
                )
            }
        }
    }

}

@Composable
private fun PushNote(text: String, action: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

@Composable
private fun SetupCommands(commands: String, onCopy: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.push_setup_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.push_setup_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            commands,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            FilledTonalButton(onClick = { onCopy(commands) }) {
                Icon(Icons.Filled.ContentCopy, null)
                Spacer(Modifier.padding(start = 6.dp))
                Text(stringResource(R.string.push_setup_copy))
            }
        }
    }
}
