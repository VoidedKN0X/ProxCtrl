package com.vknxworks.proxctrl.ui.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
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
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vknxworks.proxctrl.MainActivity
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.widgets.Health
import com.vknxworks.proxctrl.ui.components.relativeTime

/**
 * Ctrl / Signal colours for the widgets. They're colour resources with a values-night variant, so
 * the widgets switch between Signal light and dark with the phone's theme, like the app.
 */
internal object WidgetColors {
    val background = ColorProvider(R.color.signal_widget_bg)
    val border = ColorProvider(R.color.signal_widget_border)
    val text = ColorProvider(R.color.signal_widget_text)
    val muted = ColorProvider(R.color.signal_widget_muted)
    val stopped = ColorProvider(R.color.signal_widget_stopped)
    /** The accent as text or a dot: readable on white in light mode. */
    val accentText = ColorProvider(R.color.signal_widget_accent_text)
    val warning = ColorProvider(R.color.signal_widget_warn)
    val critical = ColorProvider(R.color.signal_widget_error)
    val track = ColorProvider(R.color.signal_widget_track)
    val fill = ColorProvider(R.color.signal_widget_fill)
    val button = ColorProvider(R.color.signal_widget_button)
    /** The accent fill and the content on it (the same in both themes). */
    val accent = ColorProvider(R.color.signal_accent)
    val onAccent = ColorProvider(R.color.signal_on_accent)
}

internal fun healthColor(h: Health): ColorProvider = when (h) {
    Health.OK -> WidgetColors.accentText
    Health.WARNING -> WidgetColors.warning
    Health.CRITICAL, Health.OFFLINE -> WidgetColors.critical
    Health.NO_SERVER -> WidgetColors.stopped
}

internal fun statusColor(status: String): ColorProvider = when (status) {
    "running" -> WidgetColors.accentText
    "paused", "suspended" -> WidgetColors.warning
    "stopped" -> WidgetColors.stopped
    else -> WidgetColors.warning
}

/** Mono, upper-case label text, like the app's section labels. */
internal fun widgetLabel(color: ColorProvider = WidgetColors.muted) =
    TextStyle(color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)

/** The card behind every widget: Signal surface with a 1dp border and 22dp corners. */
@Composable
internal fun SignalWidgetFrame(modifier: GlanceModifier = GlanceModifier, padding: Int = 14, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(22.dp)
            .background(WidgetColors.border)
            .padding(1.dp),
    ) {
        Box(
            GlanceModifier
                .fillMaxSize()
                .cornerRadius(21.dp)
                .background(WidgetColors.background)
                .padding(padding.dp),
        ) { content() }
    }
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

/** A small Signal status square (2dp corners). */
@Composable
internal fun Dot(color: ColorProvider, size: Int = 8) {
    Box(GlanceModifier.size(size.dp).cornerRadius(2.dp).background(color)) {}
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
    SignalWidgetFrame(GlanceModifier.clickable(actionStartActivity(openOverviewIntent(context)))) {
        Column(
            GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                context.getString(R.string.widget_locked),
                style = TextStyle(color = WidgetColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                context.getString(R.string.widget_locked_subtitle),
                style = TextStyle(color = WidgetColors.muted, fontSize = 12.sp),
                maxLines = 2,
            )
        }
    }
}
