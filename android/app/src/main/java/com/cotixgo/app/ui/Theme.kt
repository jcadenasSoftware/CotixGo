package com.cotixgo.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Identidad CotixGo según la referencia visual: azul marino + azul
// brillante + ámbar sobre superficies claras (uso en campo, legibilidad).
val CotixGoNavy = Color(0xFF0A1633)
val CotixGoBlue = Color(0xFF2E7CF6)
val CotixGoAmber = Color(0xFFFF9F2E)

// Tonos suaves para tarjetas de estado e insignias.
val CotixGoBlueSoft = Color(0xFFDCE9FF)
val CotixGoAmberSoft = Color(0xFFFFEACC)
val CotixGoGreen = Color(0xFF1E8E4E)
val CotixGoGreenSoft = Color(0xFFE1F3E8)
val CotixGoSlate = Color(0xFF54617E)

private val CotixGoColorScheme = lightColorScheme(
    primary = CotixGoBlue,
    onPrimary = Color.White,
    primaryContainer = CotixGoBlueSoft,
    onPrimaryContainer = CotixGoNavy,
    secondary = CotixGoSlate,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EBF5),
    onSecondaryContainer = CotixGoNavy,
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF16233F),
    surface = Color.White,
    onSurface = Color(0xFF16233F),
    surfaceVariant = Color(0xFFE8EDF6),
    onSurfaceVariant = Color(0xFF4A5670),
    outline = Color(0xFFC3CBDC),
)

@Composable
fun CotixGoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CotixGoColorScheme, content = content)
}
