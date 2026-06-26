package com.cinefx.videoeditor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CinematicColorScheme = darkColorScheme(
    primary = NeonCyan,
    secondary = DeepViolet,
    tertiary = HotPink,
    background = SpaceObsidian,
    surface = SurfaceDarkBlue,
    onPrimary = SpaceObsidian,
    onSecondary = TextLight,
    onTertiary = TextLight,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = SurfaceDarkBlue,
    onSurfaceVariant = TextMuted
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CinematicColorScheme,
        typography = Typography,
        content = content
    )
}
