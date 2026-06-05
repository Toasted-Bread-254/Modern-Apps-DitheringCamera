package com.vayunmathur.games.pipes.ui

import androidx.compose.ui.graphics.Color

data class PipeColor(val main: Color, val dark: Color, val light: Color)

val PIPE_COLORS = listOf(
    PipeColor(Color(0xFFE53935), Color(0xFFB71C1C), Color(0xFFFF8A80)),   // Red
    PipeColor(Color(0xFF43A047), Color(0xFF1B5E20), Color(0xFFA5D6A7)),   // Green
    PipeColor(Color(0xFF1E88E5), Color(0xFF0D47A1), Color(0xFF90CAF9)),   // Blue
    PipeColor(Color(0xFFFDD835), Color(0xFFF9A825), Color(0xFFFFF59D)),   // Yellow
    PipeColor(Color(0xFFFF8F00), Color(0xFFE65100), Color(0xFFFFCC80)),   // Orange
    PipeColor(Color(0xFF00ACC1), Color(0xFF006064), Color(0xFF80DEEA)),   // Cyan
    PipeColor(Color(0xFFAB47BC), Color(0xFF6A1B9A), Color(0xFFCE93D8)),   // Magenta
    PipeColor(Color(0xFF8D6E63), Color(0xFF4E342E), Color(0xFFBCAAA4)),   // Maroon
    PipeColor(Color(0xFF7CB342), Color(0xFF33691E), Color(0xFFC5E1A5)),   // Lime
    PipeColor(Color(0xFF00897B), Color(0xFF004D40), Color(0xFF80CBC4)),   // Teal
)
