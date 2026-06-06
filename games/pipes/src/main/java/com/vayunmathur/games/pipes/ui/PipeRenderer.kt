package com.vayunmathur.games.pipes.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

enum class Direction { UP, DOWN, LEFT, RIGHT }

fun DrawScope.drawEmptyCell(cellRect: Rect) {
    drawRect(
        color = Color(0xFF2D2D2D),
        topLeft = cellRect.topLeft,
        size = cellRect.size
    )
    drawRect(
        color = Color(0xFF3A3A3A),
        topLeft = cellRect.topLeft,
        size = cellRect.size,
        style = Stroke(width = 1f)
    )
}

fun DrawScope.drawPipeSegment(
    cellRect: Rect,
    connections: Set<Direction>,
    pipeColor: PipeColor
) {
    val inset = cellRect.size.width * 0.15f
    val pipeWidth = cellRect.size.width - inset * 2

    for (dir in connections) {
        drawPipeArm(cellRect, dir, pipeColor, pipeWidth, inset)
    }

    if (connections.isNotEmpty()) {
        val centerRect = Rect(
            cellRect.left + inset,
            cellRect.top + inset,
            cellRect.right - inset,
            cellRect.bottom - inset
        )
        drawRoundRect(
            brush = createMetallicBrush(centerRect, pipeColor),
            topLeft = centerRect.topLeft,
            size = centerRect.size,
            cornerRadius = CornerRadius(pipeWidth * 0.15f)
        )
    }
}

fun DrawScope.drawEndpointBall(cellRect: Rect, pipeColor: PipeColor) {
    val inset = cellRect.size.width * 0.15f
    val pipeWidth = cellRect.size.width - inset * 2
    val center = cellRect.center
    val radius = pipeWidth * 0.45f

    drawCircle(
        color = pipeColor.dark,
        radius = radius + 2f,
        center = center
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(pipeColor.light, pipeColor.main, pipeColor.dark),
            center = Offset(center.x - radius * 0.2f, center.y - radius * 0.2f),
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

private fun DrawScope.drawPipeArm(
    cellRect: Rect,
    direction: Direction,
    pipeColor: PipeColor,
    pipeWidth: Float,
    inset: Float
) {
    val armRect = when (direction) {
        Direction.UP -> Rect(
            cellRect.left + inset, cellRect.top,
            cellRect.right - inset, cellRect.top + inset + pipeWidth * 0.3f
        )
        Direction.DOWN -> Rect(
            cellRect.left + inset, cellRect.bottom - inset - pipeWidth * 0.3f,
            cellRect.right - inset, cellRect.bottom
        )
        Direction.LEFT -> Rect(
            cellRect.left, cellRect.top + inset,
            cellRect.left + inset + pipeWidth * 0.3f, cellRect.bottom - inset
        )
        Direction.RIGHT -> Rect(
            cellRect.right - inset - pipeWidth * 0.3f, cellRect.top + inset,
            cellRect.right, cellRect.bottom - inset
        )
    }

    drawRect(
        brush = createMetallicBrush(armRect, pipeColor),
        topLeft = armRect.topLeft,
        size = armRect.size
    )
}

private fun createMetallicBrush(rect: Rect, pipeColor: PipeColor): Brush {
    return Brush.linearGradient(
        colorStops = arrayOf(
            0f to pipeColor.dark,
            0.2f to pipeColor.main,
            0.45f to pipeColor.light,
            0.8f to pipeColor.main,
            1f to pipeColor.dark
        ),
        start = Offset(rect.left, rect.top),
        end = Offset(rect.right, rect.bottom)
    )
}
