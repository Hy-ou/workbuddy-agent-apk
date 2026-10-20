package com.workbuddy2api.panel.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 面板语义色扩展：Material3 的 ColorScheme 装不下「ok / warn / bad」这类业务状态色。 */
@Immutable
data class WbColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val raise: Color,
    val line: Color,
    val lineSoft: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val accentSoft: Color,
    val ok: Color,
    val okSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val bad: Color,
    val badSoft: Color,
    val isDark: Boolean,
)

internal val DarkWbColors = WbColors(
    bg = DarkBg, surface = DarkSurface, surface2 = DarkSurface2, raise = DarkRaise,
    line = DarkLine, lineSoft = DarkLineSoft,
    ink = DarkInk, ink2 = DarkInk2, ink3 = DarkInk3,
    accent = DarkAccent, accentSoft = DarkAccentSoft,
    ok = DarkOk, okSoft = DarkOkSoft,
    warn = DarkWarn, warnSoft = DarkWarnSoft,
    bad = DarkBad, badSoft = DarkBadSoft,
    isDark = true,
)

internal val LightWbColors = WbColors(
    bg = LightBg, surface = LightSurface, surface2 = LightSurface2, raise = LightRaise,
    line = LightLine, lineSoft = LightLineSoft,
    ink = LightInk, ink2 = LightInk2, ink3 = LightInk3,
    accent = LightAccent, accentSoft = LightAccentSoft,
    ok = LightOk, okSoft = LightOkSoft,
    warn = LightWarn, warnSoft = LightWarnSoft,
    bad = LightBad, badSoft = LightBadSoft,
    isDark = false,
)

val LocalWbColors = staticCompositionLocalOf { DarkWbColors }
