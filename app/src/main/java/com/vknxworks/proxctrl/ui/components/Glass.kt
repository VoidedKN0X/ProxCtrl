package com.vknxworks.proxctrl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The blurred backdrop shared by floating glass surfaces (tab bar). Null when not available. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * Space taken at the bottom of the screen by floating bars. Scrolling screens add it to their
 * bottom content padding, so their last items can scroll clear of the glass.
 */
val LocalBottomClearance = compositionLocalOf { 0.dp }

/**
 * Frosted glass: blurs whatever scrolls behind, tints it with the surface colour, and adds a
 * hairline edge and soft shadow so it floats. Falls back to a translucent fill where blur isn't available.
 */
@Composable
fun Modifier.glass(shape: Shape, elevation: Dp = 18.dp, tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh): Modifier {
    val state = LocalHazeState.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val edge = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    val highlight = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.35f)
    val base = this
        .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
        .clip(shape)
    val blurred = if (state != null) {
        base.hazeEffect(
            state,
            HazeStyle(
                backgroundColor = MaterialTheme.colorScheme.background,
                tint = HazeTint(tint.copy(alpha = if (dark) 0.62f else 0.70f)),
                blurRadius = 28.dp,
                noiseFactor = 0.04f,
                fallbackTint = HazeTint(tint.copy(alpha = 0.94f)),
            ),
        )
    } else {
        base.background(tint.copy(alpha = 0.96f))
    }
    // A faint top-down sheen and a hairline border give the edge some depth.
    return blurred
        .background(Brush.verticalGradient(listOf(highlight, Color.Transparent)))
        .border(1.dp, edge, shape)
}
