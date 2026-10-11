package com.cotixgo.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Identidad CotixGo según la referencia visual: azul marino + acento naranja.
private val CotixGoNavy = Color(0xFF0A1633)
private val CotixGoBlue = Color(0xFF2E7CF6)
private val CotixGoOrange = Color(0xFFFF9F2E)
private val CotixGoSurface = Color(0xFF0F1E42)
private val CotixGoOnSurface = Color(0xFFE9EEF8)

private val CotixGoColorScheme = darkColorScheme(
    primary = CotixGoBlue,
    secondary = CotixGoOrange,
    background = CotixGoNavy,
    surface = CotixGoSurface,
    onPrimary = Color.White,
    onSecondary = CotixGoNavy,
    onBackground = CotixGoOnSurface,
    onSurface = CotixGoOnSurface,
)

@Composable
fun CotixGoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CotixGoColorScheme, content = content)
}
