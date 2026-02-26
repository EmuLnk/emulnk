package com.emulnk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val EmuLnkColorScheme = darkColorScheme(
    primary = BrandPurple,
    secondary = BrandCyan,
    tertiary = SurfaceOverlay,
    background = SurfaceBase,
    surface = SurfaceRaised,
    surfaceVariant = SurfaceElevated,
    onPrimary = TextPrimary,
    onSecondary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = StatusError,
    onError = TextPrimary,
    outline = DividerColor,
    outlineVariant = DividerColor
)

object EmuLnkDimens {
    val spacingXs = 4.dp
    val spacingSm = 8.dp
    val spacingMd = 12.dp
    val spacingLg = 16.dp
    val spacingXl = 24.dp
    val spacingXxl = 32.dp

    val cornerSm = 8.dp
    val cornerMd = 16.dp
    val cornerLg = 24.dp
}

@Composable
fun EmuLinkTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = EmuLnkColorScheme,
        typography = Typography,
        content = content
    )
}
