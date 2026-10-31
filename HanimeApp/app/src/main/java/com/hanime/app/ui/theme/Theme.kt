package com.hanime.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app is dark-first: cover art is the only saturated colour on screen, so the
 * chrome stays near-black and lets thumbnails carry the palette.
 */
object Ink {
    val Bg = Color(0xFF07070A)
    val BgElevated = Color(0xFF101017)
    val Surface = Color(0xFF15151D)
    val SurfaceHigh = Color(0xFF1D1D27)
    val Outline = Color(0x2EFFFFFF)
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xB3F5F5F7)
    val TextTertiary = Color(0x66F5F5F7)
    val Accent = Color(0xFFFF5B7A)
    val AccentSoft = Color(0xFFFF8FA3)
    val Mint = Color(0xFF64E2B7)
    val Sky = Color(0xFF6FB8FF)
}

private val DarkScheme = darkColorScheme(
    primary = Ink.Accent,
    onPrimary = Color.White,
    primaryContainer = Ink.Accent,
    onPrimaryContainer = Color.White,
    secondary = Ink.Mint,
    onSecondary = Color(0xFF04231A),
    tertiary = Ink.Sky,
    background = Ink.Bg,
    onBackground = Ink.TextPrimary,
    surface = Ink.Surface,
    onSurface = Ink.TextPrimary,
    surfaceVariant = Ink.SurfaceHigh,
    onSurfaceVariant = Ink.TextSecondary,
    surfaceContainerLowest = Color(0xFF050508),
    surfaceContainerLow = Ink.BgElevated,
    surfaceContainer = Ink.Surface,
    surfaceContainerHigh = Ink.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF262633),
    outline = Ink.Outline,
    outlineVariant = Color(0x1AFFFFFF),
    error = Color(0xFFFF6B6B),
    onError = Color.White,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFE23A5E),
    onPrimary = Color.White,
    secondary = Color(0xFF11826A),
    background = Color(0xFFF6F6F9),
    onBackground = Color(0xFF111116),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111116),
    surfaceVariant = Color(0xFFECECF2),
    onSurfaceVariant = Color(0xFF4A4A55),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFD),
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFE9E9F0),
    surfaceContainerHighest = Color(0xFFDFDFE8),
    outline = Color(0x1F000000),
    outlineVariant = Color(0x14000000),
    error = Color(0xFFD93025),
    onError = Color.White,
)

@Composable
fun HanimeTheme(dark: Boolean = true, content: @Composable () -> Unit) {    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
