package com.vknxworks.proxctrl.ui.widgets

import com.vknxworks.proxctrl.ui.theme.isSystemDark
import com.vknxworks.proxctrl.ui.components.CtrlButton
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.vknxworks.proxctrl.ui.components.LocalAppCovered
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.lock.AppLock
import com.vknxworks.proxctrl.data.widgets.WidgetRefresher
import com.vknxworks.proxctrl.data.widgets.WidgetStore
import com.vknxworks.proxctrl.ui.components.StatusDot
import com.vknxworks.proxctrl.ui.lock.LockOverlays
import com.vknxworks.proxctrl.ui.lock.LockScreen
import com.vknxworks.proxctrl.ui.theme.ProxCtrlTheme
import kotlinx.coroutines.launch
import com.vknxworks.proxctrl.ui.components.WindowPrivacy

/**
 * Picks the guests a pinned-guests widget shows, from the active server's guest list. Opens when the
 * widget is added, and again from the widget's title.
 */
class PinnedGuestsConfigActivity : FragmentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves the widget unconfigured, so the launcher doesn't add it.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val dark = isSystemDark()
        enableEdgeToEdge(
            statusBarStyle = com.vknxworks.proxctrl.ui.theme.signalBarStyle(dark),
            navigationBarStyle = com.vknxworks.proxctrl.ui.theme.signalBarStyle(dark),
        )
        val c = container
        AppLock.attach(this, c)
        setContent {
            val settings by c.settings.settings.collectAsStateWithLifecycle()
            val lockState by AppLock.state.collectAsStateWithLifecycle()
            WindowPrivacy(this, settings.hideScreenshots || lockState != AppLock.State.UNLOCKED, hideRecents = settings.hideRecents || c.vault.enabled || AppLock.legacy)
            com.vknxworks.proxctrl.ui.theme.SignalSystemBars(this)
            ProxCtrlTheme(settings.theme, settings.dynamicColor) {
                Box(Modifier.fillMaxSize()) {
                    // Closing: no new windows (dialogs) from here on.
                    CompositionLocalProvider(LocalAppCovered provides (isFinishing || isDestroyed)) {
                        if (lockState == AppLock.State.UNLOCKED) {
                            Picker(appWidgetId, onDone = ::done, onCancel = ::finish)
                            LockOverlays(this@PinnedGuestsConfigActivity)
                        }
                        if (lockState != AppLock.State.UNLOCKED) {
                            LockScreen(this@PinnedGuestsConfigActivity)
                        }
                    }
                }
            }
        }
    }

    private fun done(vmids: List<Int>) {
        val app = applicationContext
        val id = appWidgetId
        container.appScope.launch {
            WidgetStore.setPins(app, id, vmids)
            runCatching {
                val glanceId = GlanceAppWidgetManager(app).getGlanceIdBy(id)
                PinnedGuestsWidget().update(app, glanceId)
            }
            WidgetRefresher.schedule(app)
            WidgetRefresher.refreshSoon(app)
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}

@Composable
private fun Picker(appWidgetId: Int, onDone: (List<Int>) -> Unit, onCancel: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val list by c.guests.state.collectAsStateWithLifecycle()
    val selected = remember { mutableStateListOf<Int>() }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(appWidgetId) {
        selected.clear()
        selected.addAll(WidgetStore.pins(context, appWidgetId))
        loaded = true
        scope.launch { runCatching { c.guests.refresh() } }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Text(
                stringResource(R.string.widget_config_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            )
            Text(
                if (active == null) stringResource(R.string.widget_config_no_server)
                else stringResource(R.string.widget_config_subtitle, active?.server?.name.orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            val guests = list.guests.filter { !it.isTemplate }.sortedBy { it.vmid }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (guests.isEmpty() && (list.loading || !loaded)) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(guests, key = { "${it.type.api}-${it.vmid}" }) { g ->
                            val checked = g.vmid in selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { if (checked) selected.remove(g.vmid) else selected.add(g.vmid) }
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { on -> if (on) selected.add(g.vmid) else selected.remove(g.vmid) })
                                StatusDot(g.status)
                                Spacer(Modifier.width(10.dp))
                                Text("${g.vmid}  ${g.name}", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                CtrlButton(onClick = { onDone(selected.toList()) }, enabled = active != null) {
                    Text(stringResource(R.string.widget_config_save))
                }
            }
        }
    }
}
