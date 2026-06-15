package com.vayunmathur.photos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.max

@Composable
fun HealingOverlay(
    sourceX: Float?,
    sourceY: Float?,
    brushSize: Float,
    isSettingSource: Boolean,
    onSourceSet: (Float, Float) -> Unit,
    onPaint: (Float, Float) -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isSettingSource) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val x = offset.x / size.width
                            val y = offset.y / size.height
                            onSourceSet(x, y)
                        }
                    }
                } else {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val x = change.position.x / size.width
                            val y = change.position.y / size.height
                            onPaint(x, y)
                        }
                    }
                }
            ),
    ) {
        val w = size.width
        val h = size.height

        if (sourceX != null && sourceY != null) {
            val sx = sourceX * w
            val sy = sourceY * h
            val crossSize = 12.dp.toPx()
            drawLine(Color.Cyan, Offset(sx - crossSize, sy), Offset(sx + crossSize, sy), strokeWidth = 2.dp.toPx())
            drawLine(Color.Cyan, Offset(sx, sy - crossSize), Offset(sx, sy + crossSize), strokeWidth = 2.dp.toPx())
            drawCircle(Color.Cyan, brushSize * max(w, h), Offset(sx, sy), style = Stroke(1.dp.toPx()))
        }
    }
}
