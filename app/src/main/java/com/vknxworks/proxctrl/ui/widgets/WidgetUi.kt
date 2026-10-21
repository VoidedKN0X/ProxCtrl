package com.vknxworks.proxctrl.ui.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vknxworks.proxctrl.MainActivity
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.widgets.Health
import com.vknxworks.proxctrl.ui.components.relativeTime

/** Fixed status colours (the same in light and dark), matching the app's status dots. */
internal object WidgetColors {
    val ok = Color(0xFF2E9E5B)
    val warning = Color(0xFFD08A1E)
    val critical = Color(0xFFD0443A)
    val stopped = Color(0xFF8A8F98)
    val paused = Color(0xFF3A9BD4)
}

internal fun healthColor(h: Health): Color = when (h) {
    Health.OK -> WidgetColors.ok
    Health.WARNING -> WidgetColors.warning
    Health.CRITICAL, Health.OFFLINE -> WidgetColors.critical
    Health.NO_SERVER -> WidgetColors.stopped
}

internal fun statusColor(status: String): Color = when (status) {
    "running" -> WidgetColors.ok
    "paused", "suspended" -> WidgetColors.paused
    "stopped" -> WidgetColors.stopped
    else -> WidgetColors.warning
}

internal fun healthLabel(context: Context, h: Health, issues: Int): String = when (h) {
    Health.OK -> context.getString(R.string.widget_health_ok)
    Health.WARNING, Health.CRITICAL -> context.resources.getQuantityString(R.plurals.widget_health_issues, issues, issues)
    Health.OFFLINE -> context.getString(R.string.widget_health_offline)
    Health.NO_SERVER -> context.getString(R.string.widget_no_server)
}

/** "Updated 5 minutes ago", or the stale wording when the last refresh failed. */
internal fun updatedLabel(context: Context, updatedAt: Long, stale: Boolean): String = when {
    updatedAt <= 0 -> context.getString(R.string.widget_never_updated)
    stale -> context.getString(R.string.widget_stale, relativeTime(updatedAt))
    else -> context.getString(R.string.widget_updated, relativeTime(updatedAt))
}

@Composable
internal fun Dot(color: Color, size: Int = 10) {
    Box(GlanceModifier.size(size.dp).cornerRadius((size / 2).dp).background(ColorProvider(color))) {}
}

/** Opens ProxCtrl on the Overview. */
internal fun openOverviewIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(WidgetActions.ACTION_OPEN_OVERVIEW)
        .setData(Uri.parse("proxctrl-widget://overview"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Opens a guest's page (the app's own proxctrl://guest link). */
internal fun openGuestIntent(context: Context, vmid: Int): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("proxctrl://guest/$vmid"), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Asks in the app to start or shut down a guest; never acts from the widget itself. */
internal fun confirmPowerIntent(context: Context, serverId: String, vmid: Int, action: String): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(WidgetActions.ACTION_CONFIRM_POWER)
        // A distinct URI per guest and action, so each button gets its own PendingIntent.
        .setData(Uri.parse("proxctrl-widget://power/$action/$vmid"))
        .putExtra(WidgetActions.EXTRA_SERVER, serverId)
        .putExtra(WidgetActions.EXTRA_VMID, vmid)
        .putExtra(WidgetActions.EXTRA_ACTION, action)
        .putExtra(WidgetActions.EXTRA_INTERNAL, WidgetActions.internalToken(context))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/**
 * Shown instead of a widget's content while ProxCtrl is locked and the logins are sealed (app lock on,
 * background checks off): nothing about the server, not even what was last saved. Tap opens ProxCtrl.
 */
@Composable
internal fun LockedWidgetContent() {
    val context = LocalContext.current
    Column(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(20.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .padding(14.dp)
            .clickable(actionStartActivity(openOverviewIntent(context))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            context.getString(R.string.widget_locked),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Text(
            context.getString(R.string.widget_locked_subtitle),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            maxLines = 2,
        )
    }
}
