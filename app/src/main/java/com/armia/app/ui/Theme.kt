package com.armia.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ArmColors = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    onPrimary = Color(0xFF0E1116),
    secondary = Color(0xFF7C4DFF),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE8EEF4),
    surface = Color(0xFF171C24),
    onSurface = Color(0xFFE8EEF4),
    surfaceVariant = Color(0xFF232B36),
    onSurfaceVariant = Color(0xFF9AA7B5)
)

@Composable
fun ArmIATheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ArmColors, content = content)
}
