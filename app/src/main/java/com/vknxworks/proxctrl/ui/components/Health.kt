package com.vknxworks.proxctrl.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.ui.theme.Health

/**
 * Colour for a usage level: normal below [warnAt], warning from there, critical from 95 % (or 10
 * points above [warnAt] when that's lower). [normal] is used below the warning level.
 */
fun usageColor(fraction: Double?, warnAt: Int, normal: Color): Color {
    if (fraction == null) return normal
    val pct = fraction * 100
    val critical = minOf(95, warnAt + 10)
    return when {
        pct >= critical -> Health.critical
        pct >= warnAt -> Health.warn
        else -> normal
    }
}

/** A thin rounded usage bar. Null [fraction] draws an empty track (unknown). */
@Composable
fun UsageBar(fraction: Double?, color: Color, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, Offset.Zero, size, r)
        val f = (fraction ?: 0.0).coerceIn(0.0, 1.0).toFloat()
        if (f > 0f) drawRoundRect(color, Offset.Zero, Size(size.width * f, size.height), r)
    }
}

/** A guest's or node's status as a coloured dot. */
@Composable
fun StatusDot(status: String, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val color = when (status) {
        "running", "online" -> Health.ok
        "paused", "suspended" -> Health.warn
        "stopped", "offline" -> Health.idle
        else -> Health.critical
    }
    Box(modifier.size(size).clip(CircleShape).background(color))
}
