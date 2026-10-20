package com.workbuddy2api.panel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

val WbMono = FontFamily.Monospace

private fun darkScheme(c: WbColors): ColorScheme = darkColorScheme(
    primary = c.accent,
    onPrimary = Color.White,
    primaryContainer = c.accentSoft,
    onPrimaryContainer = c.accent,
    secondary = c.ink2,
    onSecondary = c.bg,
    secondaryContainer = c.surface2,
    onSecondaryContainer = c.ink,
    tertiary = c.ok,
    onTertiary = c.bg,
    background = c.bg,
    onBackground = c.ink,
    surface = c.surface,
    onSurface = c.ink,
    surfaceVariant = c.surface2,
    onSurfaceVariant = c.ink2,
    surfaceContainerLowest = c.bg,
    surfaceContainerLow = c.surface,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surface2,
    surfaceContainerHighest = c.raise,
    outline = c.line,
    outlineVariant = c.lineSoft,
    error = c.bad,
    onError = Color.White,
    errorContainer = c.badSoft,
    onErrorContainer = c.bad,
    inverseSurface = c.ink,
    inverseOnSurface = c.bg,
    scrim = Color.Black,
)

private fun lightScheme(c: WbColors): ColorScheme = lightColorScheme(
    primary = c.accent,
    onPrimary = Color.White,
    primaryContainer = c.accentSoft,
    onPrimaryContainer = c.accent,
    secondary = c.ink2,
    onSecondary = Color.White,
    secondaryContainer = c.surface2,
    onSecondaryContainer = c.ink,
    tertiary = c.ok,
    onTertiary = Color.White,
    background = c.bg,
    onBackground = c.ink,
    surface = c.surface,
    onSurface = c.ink,
    surfaceVariant = c.surface2,
    onSurfaceVariant = c.ink2,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = c.surface,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surface2,
    surfaceContainerHighest = c.raise,
    outline = c.line,
    outlineVariant = c.lineSoft,
    error = c.bad,
    onError = Color.White,
    errorContainer = c.badSoft,
    onErrorContainer = c.bad,
    inverseSurface = c.ink,
    inverseOnSurface = c.surface,
    scrim = Color.Black,
)

@Composable
fun WbTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val wb = if (darkTheme) DarkWbColors else LightWbColors
    CompositionLocalProvider(LocalWbColors provides wb) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme(wb) else lightScheme(wb),
            typography = WbTypography,
            content = content,
        )
    }
}

/** 常用间距：4 的倍数，手机端卡片内边距统一 14–16dp。 */
object WbDimens {
    val screenPadding = 16.dp
    val cardRadius = 16.dp
    val cardPadding = 14.dp
    val gap = 12.dp
    val gapSmall = 8.dp
    val bottomBarHeight = 64.dp
}
