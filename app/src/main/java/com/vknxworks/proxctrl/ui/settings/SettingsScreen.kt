package com.vknxworks.proxctrl.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vknxworks.proxctrl.data.lock.AppLock
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.settings.ThemeChoice
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupChoice
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import com.vknxworks.proxctrl.ui.nav.NotificationsRoute
import com.vknxworks.proxctrl.ui.components.GroupSwitch
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import kotlinx.coroutines.launch

/** App settings: notifications, appearance, warning thresholds, privacy, app lock, widgets and usage stats. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val themeOptions = ThemeChoice.entries.map { it to stringResource(it.label) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.settings))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Group {
                GroupRow(
                    stringResource(R.string.notifications),
                    stringResource(R.string.notifications_subtitle),
                    icon = Icons.Filled.Notifications,
                    showChevron = true,
                    onClick = { nav.navigate(NotificationsRoute) },
                )
            }
            Group(stringResource(R.string.group_appearance)) {
                GroupChoice(
                    stringResource(R.string.theme),
                    themeOptions,
                    settings.theme,
                    icon = Icons.Filled.DarkMode,
                ) { choice -> c.appScope.launch { c.settings.update { it.copy(theme = choice) } } }
                GroupSwitch(
                    stringResource(R.string.dynamic_colour),
                    stringResource(R.string.dynamic_colour_subtitle),
                    checked = settings.dynamicColor,
                    icon = Icons.Filled.Palette,
                ) { on -> c.appScope.launch { c.settings.update { it.copy(dynamicColor = on) } } }
            }
            Group(stringResource(R.string.group_warnings)) {
                GroupChoice(
                    stringResource(R.string.disk_warn_title),
                    listOf(70, 75, 80, 85, 90, 95).map { it to stringResource(R.string.disk_warn_subtitle, it) },
                    settings.diskWarnPercent,
                    icon = Icons.Filled.Storage,
                ) { pct -> c.appScope.launch { c.settings.update { it.copy(diskWarnPercent = pct) } } }
                GroupChoice(
                    stringResource(R.string.mem_warn_title),
                    listOf(75, 80, 85, 90, 95).map { it to stringResource(R.string.mem_warn_subtitle, it) },
                    settings.memWarnPercent,
                    icon = Icons.Filled.Memory,
                ) { pct -> c.appScope.launch { c.settings.update { it.copy(memWarnPercent = pct) } } }
                GroupChoice(
                    stringResource(R.string.backup_age_title),
                    listOf(1, 2, 3, 7, 14, 30).map { it to pluralStringResource(R.plurals.backup_age_subtitle, it, it) },
                    settings.backupMaxAgeDays,
                    icon = Icons.Filled.Backup,
                ) { d -> c.appScope.launch { c.settings.update { it.copy(backupMaxAgeDays = d) } } }
            }
            Group(stringResource(R.string.group_privacy), footer = stringResource(R.string.hide_content_footer)) {
                GroupSwitch(
                    stringResource(R.string.hide_content),
                    stringResource(R.string.hide_content_subtitle),
                    checked = settings.hideContent,
                    icon = Icons.Filled.VisibilityOff,
                ) { on -> c.appScope.launch { c.settings.update { it.copy(hideContent = on) } } }
            }
            val lockAvailable = remember { AppLock.isAvailable(context) }
            Group(stringResource(R.string.lock_group), footer = stringResource(if (lockAvailable) R.string.lock_footer else R.string.lock_unavailable)) {
                GroupSwitch(
                    stringResource(R.string.lock_on_open),
                    stringResource(R.string.lock_on_open_subtitle),
                    checked = settings.lockOnOpen && lockAvailable,
                    icon = Icons.Filled.Fingerprint,
                ) { on -> if (lockAvailable) c.appScope.launch { c.settings.update { it.copy(lockOnOpen = on) } } }
                GroupSwitch(
                    stringResource(R.string.lock_changes),
                    stringResource(R.string.lock_changes_subtitle),
                    checked = settings.lockChanges && lockAvailable,
                    icon = Icons.Filled.Lock,
                ) { on -> if (lockAvailable) c.appScope.launch { c.settings.update { it.copy(lockChanges = on) } } }
            }
            Group(stringResource(R.string.widget_settings_group)) {
                GroupRow(
                    stringResource(R.string.widget_settings_title),
                    stringResource(R.string.widget_settings_subtitle),
                    icon = Icons.Filled.Widgets,
                )
            }
            if (c.telemetry.available) {
                Group(stringResource(R.string.group_usage_stats), footer = stringResource(R.string.usage_stats_description)) {
                    GroupSwitch(
                        stringResource(R.string.usage_stats_switch),
                        stringResource(R.string.usage_stats_switch_subtitle),
                        checked = settings.usageStats,
                        icon = Icons.Filled.Insights,
                    ) { on -> c.appScope.launch { c.settings.update { it.copy(usageStats = on, usageStatsAsked = true) } } }
                }
            }
        }
    }
}
