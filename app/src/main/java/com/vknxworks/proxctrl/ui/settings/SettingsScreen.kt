package com.vknxworks.proxctrl.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.settings.ThemeChoice
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupChoice
import com.vknxworks.proxctrl.ui.components.GroupSwitch
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import kotlinx.coroutines.launch

/** App settings. Server, notification, lock and threshold settings are added in later stages. */
@Composable
fun SettingsScreen() {
    val c = LocalContext.current.container
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val themeOptions = ThemeChoice.entries.map { it to stringResource(it.label) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.settings))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
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
            }
        }
    }
}
