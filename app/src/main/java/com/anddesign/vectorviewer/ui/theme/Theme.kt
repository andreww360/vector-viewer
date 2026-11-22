package com.anddesign.vectorviewer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF6D00)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = Color(0xFFFFB74D),
    background = Color(0xFF121212),
    surface = Color(0xFF181818),
    surfaceVariant = Color(0xFF252525),
    surfaceContainer = Color(0xFF1E1E1E),
    onSurface = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFFB0B0B0),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFE65100),
    onPrimary = Color.White,
    secondary = Color(0xFFFF8F00),
)

@Composable
fun VectorViewerTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
