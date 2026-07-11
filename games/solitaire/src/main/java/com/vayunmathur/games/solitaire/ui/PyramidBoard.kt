package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.solitaire.data.PyramidState
import com.vayunmathur.games.solitaire.util.SolitaireViewModel

private val SelectionColor = Color(0xFFFFC107)

@Composable
fun PyramidBoard(state: PyramidState, viewModel: SolitaireViewModel, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val cardWidth = (maxWidth - 8.dp) / 7
        val cardHeight = cardWidth * 1.4f
        val verticalStep = cardHeight * 0.5f

        Column {
            // Triangular pyramid. Rows are drawn top-to-bottom so the cards that
            // rest on top (lower rows) overlap the ones behind them.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(cardHeight + verticalStep * (state.pyramid.size - 1))
            ) {
                state.pyramid.forEachIndexed { r, row ->
                    row.forEachIndexed { c, card ->
                        if (card != null) {
                            val id = "pyr_${r}_$c"
                            val exposed = isExposed(state, r, c)
                            val xOffset = cardWidth * (c + 3f - r / 2f)
                            val yOffset = verticalStep * r
                            Box(
                                Modifier.offset(x = xOffset, y = yOffset)
                            ) {
                                SelectableCard(
                                    selected = state.selectedId == id,
                                    enabled = exposed,
                                    onClick = { viewModel.pyramidTapCard(id) },
                                    cardWidth = cardWidth,
                                    cardHeight = cardHeight
                                ) {
                                    CardFace(card, cardWidth = cardWidth, cardHeight = cardHeight)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stock: deal one card to the waste, or recycle when empty.
                val canRecycle = state.passesRemaining > 0 && state.waste.isNotEmpty()
                if (state.stock.isNotEmpty()) {
                    CardBack(
                        modifier = Modifier.clickable { viewModel.pyramidDealStock() },
                        cardWidth = cardWidth,
                        cardHeight = cardHeight
                    )
                } else {
                    EmptySlot(
                        modifier = Modifier.clickable(enabled = canRecycle) { viewModel.pyramidDealStock() },
                        label = if (canRecycle) "↻" else "",
                        cardWidth = cardWidth,
                        cardHeight = cardHeight
                    )
                }

                // Waste: the top card is playable.
                val wasteTop = state.waste.lastOrNull()
                if (wasteTop != null) {
                    SelectableCard(
                        selected = state.selectedId == "waste",
                        enabled = true,
                        onClick = { viewModel.pyramidTapCard("waste") },
                        cardWidth = cardWidth,
                        cardHeight = cardHeight
                    ) {
                        CardFace(wasteTop, cardWidth = cardWidth, cardHeight = cardHeight)
                    }
                } else {
                    EmptySlot(cardWidth = cardWidth, cardHeight = cardHeight)
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    "Recycles left: ${state.passesRemaining}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** A card that can be tapped; shows a highlight border when [selected] and is
 * dimmed via a disabled click when not [enabled]. */
@Composable
private fun SelectableCard(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    cardWidth: androidx.compose.ui.unit.Dp,
    cardHeight: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit
) {
    val scale = cardWidth.value / CARD_WIDTH.value
    val corner = (8 * scale).dp
    var m = Modifier
        .width(cardWidth)
        .height(cardHeight)
        .clickable(enabled = enabled, onClick = onClick)
    if (selected) {
        m = m.border(3.dp, SelectionColor, RoundedCornerShape(corner))
    }
    Box(m) { content() }
}

private fun isExposed(state: PyramidState, row: Int, col: Int): Boolean {
    if (state.pyramid[row][col] == null) return false
    if (row == state.pyramid.lastIndex) return true
    val below = state.pyramid[row + 1]
    return below[col] == null && below[col + 1] == null
}
