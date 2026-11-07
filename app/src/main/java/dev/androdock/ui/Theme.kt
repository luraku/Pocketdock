package dev.androdock.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF4F8CFF)
val Running = Color(0xFF3FB950)
val Stopped = Color(0xFF8B949E)
val Failed = Color(0xFFF85149)

private val Dark = darkColorScheme(
    primary = Accent,
    secondary = Color(0xFF8FB6FF),
    background = Color(0xFF101218),
    surface = Color(0xFF161922),
    surfaceVariant = Color(0xFF1F2330),
    surfaceContainer = Color(0xFF1A1D27),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2F6FE4),
    secondary = Color(0xFF4F8CFF),
)

@Composable
fun AndrodockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
