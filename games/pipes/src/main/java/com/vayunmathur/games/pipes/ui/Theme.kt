package com.vayunmathur.games.pipes.ui

import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PipesDarkColorScheme = darkColorScheme(
    primary = Color(0xFF4A4A4A),
    secondary = Color(0xFF3A3A3A),
    tertiary = Color(0xFF607D8B),
    background = Color(0xFF1A1A1A),
    surface = Color(0xFF2D2D2D),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    primaryContainer = Color(0xFF546E7A),
    secondaryContainer = Color(0xFF37474F),
    error = Color(0xFFCF6679)
)

@Composable
fun PipesTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PipesDarkColorScheme,
        content = content
    )
}
