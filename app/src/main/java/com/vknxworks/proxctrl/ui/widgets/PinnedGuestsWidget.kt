package com.vknxworks.proxctrl.ui.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.widgets.GuestSnapshot
import com.vknxworks.proxctrl.data.widgets.WidgetSnapshot
import com.vknxworks.proxctrl.data.widgets.WidgetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget: the guests picked for it, with status dots and Start / Shut down buttons.
 * The buttons open ProxCtrl to confirm; nothing is started or stopped from the home screen itself.
 */
class PinnedGuestsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Locked with sealed logins: no guest names or states, not even the last saved ones.
        if (context.container.backgroundBlocked()) {
            provideContent { GlanceTheme { LockedWidgetContent() } }
        }
        val appWidgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)
        val snapshot = WidgetStore.read(context)
        val pins = WidgetStore.pins(context, appWidgetId)
        provideContent {
            GlanceTheme {
                PinnedContent(snapshot, pins, appWidgetId)
            }
        }
    }
}

@Composable
private fun PinnedContent(s: WidgetSnapshot, pins: List<Int>, appWidgetId: Int) {
    val context = LocalContext.current
    val byId = s.guests.associateBy { it.vmid }
    Column(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(20.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .padding(12.dp),
    ) {
        Row(
            GlanceModifier.fillMaxWidth().clickable(actionStartActivity(configureIntent(context, appWidgetId))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                s.serverName.ifBlank { context.getString(R.string.widget_pinned_title) },
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                updatedLabel(context, s.updatedAt, s.isStale),
                style = TextStyle(color = if (s.isStale) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        if (pins.isEmpty()) {
            Box(
                GlanceModifier.fillMaxSize().clickable(actionStartActivity(configureIntent(context, appWidgetId))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    context.getString(R.string.widget_pinned_empty),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                )
            }
        } else {
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(pins, itemId = { it.toLong() }) { vmid ->
                    GuestRow(s, vmid, byId[vmid])
                }
            }
        }
    }
}

@Composable
private fun GuestRow(s: WidgetSnapshot, vmid: Int, g: GuestSnapshot?) {
    val context = LocalContext.current
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(actionStartActivity(openGuestIntent(context, vmid))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val status = g?.status ?: "unknown"
        Dot(if (s.isStale) WidgetColors.stopped else statusColor(status))
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(
                g?.let { "${it.vmid}  ${it.name}" } ?: "$vmid",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                maxLines = 1,
            )
            Text(
                when {
                    g == null -> context.getString(R.string.widget_guest_missing)
                    status == "running" -> context.getString(R.string.widget_status_running)
                    status == "stopped" -> context.getString(R.string.widget_status_stopped)
                    status == "paused" || status == "suspended" -> context.getString(R.string.widget_status_paused)
                    else -> context.getString(R.string.widget_status_unknown)
                },
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                maxLines = 1,
            )
        }
        val serverId = s.serverId
        if (g != null && serverId != null && !g.isTemplate && (status == "running" || status == "stopped")) {
            val action = if (status == "running") WidgetActions.SHUTDOWN else WidgetActions.START
            Box(
                GlanceModifier
                    .cornerRadius(14.dp)
                    .background(if (action == WidgetActions.START) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable(actionStartActivity(confirmPowerIntent(context, serverId, vmid, action))),
            ) {
                Text(
                    context.getString(if (action == WidgetActions.START) R.string.widget_start else R.string.widget_shutdown),
                    style = TextStyle(
                        color = if (action == WidgetActions.START) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSecondaryContainer,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

/** The picker for this widget's guests (also opened by tapping the widget's title). */
internal fun configureIntent(context: Context, appWidgetId: Int): Intent =
    Intent(context, PinnedGuestsConfigActivity::class.java)
        .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
        .setData(android.net.Uri.parse("proxctrl-widget://configure/$appWidgetId"))
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

class PinnedGuestsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PinnedGuestsWidget()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetSchedule.update(context, refreshNow = true)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetSchedule.update(context, refreshNow = false)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val app = context.applicationContext
        scope.launch { WidgetStore.removePins(app, appWidgetIds) }
    }
}
