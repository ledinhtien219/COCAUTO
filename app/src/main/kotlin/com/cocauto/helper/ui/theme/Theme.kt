package com.cocauto.helper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ClashDarkScheme = darkColorScheme(
    primary = ClashGold,
    onPrimary = Color(0xFF1F1600),
    primaryContainer = Color(0xFF3B2D0B),
    onPrimaryContainer = ClashGold,
    secondary = ClashSky,
    onSecondary = Color(0xFF001E2C),
    secondaryContainer = Color(0xFF102A3A),
    onSecondaryContainer = ClashSky,
    tertiary = ClashElixir,
    onTertiary = Color.White,
    background = SlateBg,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    error = ClashCrimson,
    onError = Color.White,
)

@Composable
fun CoCAutoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ClashDarkScheme,
        typography = Typography,
        content = content,
    )
}
