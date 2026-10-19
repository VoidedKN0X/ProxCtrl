package com.vknxworks.proxctrl.ui.widgets

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.widgets.Health
import com.vknxworks.proxctrl.data.widgets.WidgetRefresher
import com.vknxworks.proxctrl.data.widgets.WidgetStore
import com.vknxworks.proxctrl.ui.components.percent

/**
 * Quick Settings tile: the node's health at a glance (from the same saved state as the widgets),
 * with CPU and RAM underneath. Tapping it opens the Overview.
 */
class HealthTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val s = WidgetStore.readBlocking(this)
        val tile = qsTile ?: return
        val node = s.node
        tile.label = node?.name ?: s.serverName.ifBlank { getString(R.string.tile_label) }
        tile.subtitle = when {
            s.health == Health.NO_SERVER -> getString(R.string.widget_no_server)
            s.health == Health.OFFLINE -> getString(R.string.widget_health_offline)
            node == null -> getString(R.string.widget_no_data)
            else -> {
                val base = getString(R.string.tile_subtitle, percent(node.cpu), node.memFraction?.let(::percent) ?: 0)
                when {
                    s.isStale -> getString(R.string.tile_subtitle_stale, base)
                    s.health == Health.OK -> base
                    else -> getString(R.string.tile_subtitle_issues, resources.getQuantityString(R.plurals.widget_health_issues, s.issues, s.issues), base)
                }
            }
        }
        // Active (lit) when everything is fine; inactive when there's something to look at or no data.
        tile.state = if (s.health == Health.OK && !s.isStale) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.updateTile()
        // Old data: ask for a refresh; the tile updates again when it's done.
        if (System.currentTimeMillis() - s.checkedAt > 15 * 60_000) WidgetRefresher.refreshSoon(applicationContext)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = openOverviewIntent(this)
        val open = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        // On a locked phone, unlock first; the app may have its own lock on top.
        if (isLocked) unlockAndRun { open() } else open()
    }
}
