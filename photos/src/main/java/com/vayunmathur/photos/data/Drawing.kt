package com.vayunmathur.photos.data

import androidx.compose.ui.geometry.Offset
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import kotlinx.serialization.Serializable

@Serializable
enum class DrawingTool { Pointer, Pen, Highlighter, Eraser, Text }

fun DrawingTool.toBrush(color: Int, size: Float): Brush? = when (this) {
    DrawingTool.Pen -> Brush.createWithColorIntArgb(
        family = StockBrushes.pressurePen(),
        colorIntArgb = color,
        size = size,
        epsilon = 0.1f,
    )
    DrawingTool.Highlighter -> Brush.createWithColorIntArgb(
        family = StockBrushes.highlighter(),
        colorIntArgb = color,
        size = size,
        epsilon = 0.1f,
    )
    else -> null
}

@Serializable
data class TextElement(
    val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val rotation: Float,
    val color: Int,
    val fontSize: Float
)

@Serializable
data class SerializableOffset(val x: Float, val y: Float)

fun Offset.toSerializable() = SerializableOffset(x, y)
