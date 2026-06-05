package com.vayunmathur.games.pipes.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.util.PipesGameState

@Composable
fun GameBoard(
    levelData: LevelData,
    gameState: PipesGameState,
    activeColor: Int?,
    activePath: List<CellPos>,
    onStartDraw: (CellPos) -> Unit,
    onExtendPath: (CellPos) -> Unit,
    onCommitDraw: () -> Unit,
    isLevelWon: Boolean,
    modifier: Modifier = Modifier
) {
    val screenWidth = LocalWindowInfo.current.containerDpSize.width
    val boardSize = screenWidth - 32.dp
    val maxDim = maxOf(levelData.rows, levelData.cols)
    val cellSizeDp = boardSize / maxDim

    val cellSizePx = with(androidx.compose.ui.platform.LocalDensity.current) { cellSizeDp.toPx() }
    val boardSizePx = cellSizePx * maxDim

    val cellRects = buildMap {
        if (levelData.renderPositions != null) {
            for (cell in levelData.cells) {
                val rp = levelData.renderPositions[cell] ?: continue
                put(cell, Rect(Offset(rp.x * cellSizePx, rp.y * cellSizePx), Size(cellSizePx, cellSizePx)))
            }
        } else {
            for (cell in levelData.cells) {
                put(cell, Rect(
                    Offset(cell.col * cellSizePx, cell.row * cellSizePx),
                    Size(cellSizePx, cellSizePx)
                ))
            }
        }
    }

    val endpointSet = levelData.endpoints.flatMap { it.cells }.toSet()

    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by shimmerTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerOffset"
    )

    fun hitTest(offset: Offset): CellPos? {
        return cellRects.entries.firstOrNull { (_, rect) ->
            rect.contains(offset)
        }?.key
    }

    Canvas(
        modifier = modifier
            .size(boardSize)
            .pointerInput(levelData, isLevelWon) {
                if (isLevelWon) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pos = event.changes.firstOrNull()?.position ?: continue

                        when {
                            event.changes.any { it.pressed && !it.previousPressed } -> {
                                val cell = hitTest(pos)
                                if (cell != null) onStartDraw(cell)
                            }
                            event.changes.any { it.pressed } -> {
                                val cell = hitTest(pos)
                                if (cell != null) onExtendPath(cell)
                            }
                            event.changes.any { !it.pressed && it.previousPressed } -> {
                                onCommitDraw()
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        drawRect(Color(0xFF1A1A1A), Offset.Zero, Size(boardSizePx, boardSizePx))

        for (cell in levelData.cells) {
            val rect = cellRects[cell] ?: continue
            drawEmptyCell(rect)
        }

        val displayPaths = if (activeColor != null) {
            gameState.paths.toMutableMap().apply {
                put(activeColor, activePath)
            }
        } else {
            gameState.paths
        }

        for ((colorIndex, path) in displayPaths) {
            if (path.isEmpty()) continue
            val pipeColor = PIPE_COLORS[colorIndex % PIPE_COLORS.size]

            for (i in path.indices) {
                val cell = path[i]
                val rect = cellRects[cell] ?: continue

                val connections = mutableSetOf<Direction>()
                if (i > 0) {
                    val prev = path[i - 1]
                    directionBetween(cell, prev)?.let { connections.add(it) }
                }
                if (i < path.lastIndex) {
                    val next = path[i + 1]
                    directionBetween(cell, next)?.let { connections.add(it) }
                }

                val isEndpoint = cell in endpointSet
                drawPipeSegment(rect, connections, pipeColor, isEndpoint)
            }
        }

        if (isLevelWon) {
            val shimmerBrush = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.4f to Color.White.copy(alpha = 0.3f),
                    0.5f to Color.White.copy(alpha = 0.5f),
                    0.6f to Color.White.copy(alpha = 0.3f),
                    1f to Color.Transparent
                ),
                start = Offset(boardSizePx * (shimmerOffset - 0.3f), 0f),
                end = Offset(boardSizePx * (shimmerOffset + 0.3f), boardSizePx)
            )
            drawRect(shimmerBrush, Offset.Zero, Size(boardSizePx, boardSizePx), blendMode = BlendMode.SrcOver)
        }
    }
}

private fun directionBetween(from: CellPos, to: CellPos): Direction? {
    return when {
        to.row < from.row -> Direction.UP
        to.row > from.row -> Direction.DOWN
        to.col < from.col -> Direction.LEFT
        to.col > from.col -> Direction.RIGHT
        else -> null
    }
}
