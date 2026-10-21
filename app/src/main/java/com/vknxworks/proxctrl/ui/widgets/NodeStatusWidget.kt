package com.vknxworks.proxctrl.ui.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
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
import com.vknxworks.proxctrl.data.widgets.Health
import com.vknxworks.proxctrl.data.widgets.WidgetRefresher
import com.vknxworks.proxctrl.data.widgets.WidgetSnapshot
import com.vknxworks.proxctrl.data.widgets.WidgetStore
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.components.formatUptime
import com.vknxworks.proxctrl.ui.components.percent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Home-screen widget: the node's health, CPU, RAM, root disk and uptime. Tap opens the Overview. */
class NodeStatusWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Locked with sealed logins: show that, not the last saved state.
        if (context.container.backgroundBlocked()) {
            provideContent { GlanceTheme { LockedWidgetContent() } }
        }
        val snapshot = WidgetStore.read(context)
        provideContent {
            GlanceTheme {
                NodeStatusContent(snapshot)
            }
        }
    }
}

@Composable
private fun NodeStatusContent(s: WidgetSnapshot) {
    val context = LocalContext.current
    val node = s.node
    Column(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(20.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .padding(14.dp)
            .clickable(actionStartActivity(openOverviewIntent(context))),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Dot(healthColor(s.health))
            Spacer(GlanceModifier.width(8.dp))
            Text(
                node?.name ?: s.serverName.ifBlank { context.getString(R.string.widget_app_name) },
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (node != null && node.online && s.health != Health.OFFLINE) {
                Text(
                    context.getString(R.string.widget_uptime, formatUptime(node.uptime)),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                    maxLines = 1,
                )
            }
        }
        Text(
            healthLabel(context, s.health, s.issues),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(8.dp))
        if (node != null) {
            Meter(context.getString(R.string.widget_cpu), "${percent(node.cpu)}%", node.cpu.toFloat())
            Meter(
                context.getString(R.string.widget_ram),
                node.memFraction?.let { "${percent(it)}% · ${formatBytes(node.memUsed)}" } ?: "–",
                node.memFraction?.toFloat() ?: 0f,
            )
            Meter(
                context.getString(R.string.widget_disk),
                node.rootFraction?.let { "${percent(it)}% · ${formatBytes(node.rootUsed)}" } ?: "–",
                node.rootFraction?.toFloat() ?: 0f,
            )
        } else {
            Text(
                context.getString(if (s.serverId == null) R.string.widget_open_app_hint else R.string.widget_no_data),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            )
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(
            updatedLabel(context, s.updatedAt, s.isStale),
            style = TextStyle(
                color = if (s.isStale) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                fontSize = 11.sp,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun Meter(label: String, value: String, fraction: Float) {
    Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(GlanceModifier.fillMaxWidth()) {
            Text(label, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp), modifier = GlanceModifier.defaultWeight())
            Text(value, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        }
        Spacer(GlanceModifier.height(3.dp))
        LinearProgressIndicator(
            progress = fraction.coerceIn(0f, 1f),
            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
            color = GlanceTheme.colors.primary,
            backgroundColor = GlanceTheme.colors.surfaceVariant,
        )
    }
}

/** Keeps the refresh schedule in line with the widgets on the home screen. */
class NodeStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NodeStatusWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetSchedule.update(context, refreshNow = true)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetSchedule.update(context, refreshNow = false)
    }
}

/** Starts or stops the background refresh from the widget receivers (which can't suspend). */
internal object WidgetSchedule {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun update(context: Context, refreshNow: Boolean) {
        val app = context.applicationContext
        scope.launch {
            WidgetRefresher.schedule(app)
            if (refreshNow) WidgetRefresher.refreshSoon(app)
        }
    }
}
