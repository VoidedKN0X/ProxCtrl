package com.vknxworks.proxctrl.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.settings.ThemeChoice

// Graphite base shared by the Ctrl apps. ProxCtrl's own accent is ember.
private val Graphite = Color(0xFF121418)
private val Ember = Color(0xFFFFB37A)

/** ProxCtrl's accent. */
val ProxAccent = Ember

private val Dark = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF4A2300),
    primaryContainer = Color(0xFF5C3416),
    onPrimaryContainer = Color(0xFFFFDCC4),
    secondary = Color(0xFFC9CED8),
    onSecondary = Color(0xFF1B1E24),
    secondaryContainer = Color(0xFF2B3038),
    onSecondaryContainer = Color(0xFFECEEF2),
    tertiary = Color(0xFF8BD8C6),
    onTertiary = Color(0xFF00382D),
    background = Graphite,
    onBackground = Color(0xFFECEEF2),
    surface = Graphite,
    onSurface = Color(0xFFECEEF2),
    surfaceVariant = Color(0xFF23272E),
    onSurfaceVariant = Color(0xFF9AA1AD),
    surfaceContainerLowest = Color(0xFF0D0F12),
    surfaceContainerLow = Color(0xFF171A1F),
    surfaceContainer = Color(0xFF1C1F25),
    surfaceContainerHigh = Color(0xFF23272E),
    surfaceContainerHighest = Color(0xFF2B3038),
    outline = Color(0xFF5A616C),
    outlineVariant = Color(0xFF2E333B),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0907),
)

private val Light = lightColorScheme(
    primary = Color(0xFF9A4F12),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC4),
    onPrimaryContainer = Color(0xFF331200),
    secondary = Color(0xFF4A505B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E7EC),
    onSecondaryContainer = Color(0xFF15181D),
    tertiary = Color(0xFF0F6E5B),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF15181D),
    surface = Color(0xFFF6F7F9),
    onSurface = Color(0xFF15181D),
    surfaceVariant = Color(0xFFE4E7EC),
    onSurfaceVariant = Color(0xFF5B626E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F2F5),
    surfaceContainer = Color(0xFFEBEDF1),
    surfaceContainerHigh = Color(0xFFE4E7EC),
    surfaceContainerHighest = Color(0xFFDCE0E6),
    outline = Color(0xFF8A919C),
    outlineVariant = Color(0xFFD3D7DE),
)

private fun manrope(weight: Int) = Font(
    R.font.manrope,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(
    manrope(400), manrope(500), manrope(600), manrope(700), manrope(800),
)

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = Manrope,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
)

private val AppTypography = Typography(
    displayLarge = style(52, 56, 800, -0.03),
    displayMedium = style(44, 48, 800, -0.03),
    displaySmall = style(36, 40, 800, -0.025),
    headlineLarge = style(32, 38, 800, -0.02),
    headlineMedium = style(28, 34, 700, -0.015),
    headlineSmall = style(24, 30, 700, -0.01),
    titleLarge = style(20, 26, 700, -0.005),
    titleMedium = style(16, 22, 700),
    titleSmall = style(14, 20, 600),
    bodyLarge = style(16, 24, 400),
    bodyMedium = style(14, 21, 400),
    bodySmall = style(12, 17, 500),
    labelLarge = style(14, 20, 600),
    labelMedium = style(12, 16, 600),
    labelSmall = style(11, 14, 600, 0.01),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Horizontal page margin used everywhere. */
val Gutter = 20.dp

@Composable
fun ProxCtrlTheme(choice: ThemeChoice = ThemeChoice.SYSTEM, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK, ThemeChoice.BLACK -> true
    }
    val ctx = LocalContext.current
    var scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(ctx)
        dynamic -> dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    if (choice == ThemeChoice.BLACK) {
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0B0C0E),
            surfaceContainer = Color(0xFF131518),
            surfaceContainerHigh = Color(0xFF1A1C20),
            surfaceContainerHighest = Color(0xFF22252A),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}

/**
 * Health colours for usage bars, status dots and the attention list. They stay the same with
 * dynamic colour on, so a warning always looks like a warning.
 */
object Health {
    val ok = Color(0xFF6FCF97)
    val warn = Color(0xFFF2C94C)
    val critical = Color(0xFFFF8A80)
    val idle = Color(0xFF9AA1AD)
}
