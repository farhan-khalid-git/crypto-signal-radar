package com.crypto.signalradar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
  primary = RadarAccent,
  secondary = RadarWarning,
  tertiary = RadarDanger,
  background = RadarBackground,
  surface = RadarSurface,
  onPrimary = RadarOnPrimary,
  onSecondary = Color(0xFF1C1408),
  onTertiary = Color(0xFF2B1111),
  onBackground = RadarText,
  onSurface = RadarText,
)

@Composable
fun SignalTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = DarkColors,
    typography = RadarTypography,
    content = content,
  )
}
