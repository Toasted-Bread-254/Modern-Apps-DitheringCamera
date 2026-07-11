package com.vayunmathur.games.solitaire.data

data class TableauPile(
    val faceDown: List<Card> = emptyList(),
    val faceUp: List<Card> = emptyList()
)

enum class GameMode { KLONDIKE, SPIDER, FREECELL, PYRAMID }

enum class DrawMode { DRAW_ONE, DRAW_THREE }

data class KlondikeState(
    val stock: List<Card> = emptyList(),
    val waste: List<Card> = emptyList(),
    val tableauPiles: List<TableauPile> = List(7) { TableauPile() },
    val foundations: List<List<Card>> = List(4) { emptyList() },
    val drawMode: DrawMode = DrawMode.DRAW_ONE,
    val moveCount: Int = 0,
    val elapsedSeconds: Int = 0,
    val usedUndo: Boolean = false,
    val isWon: Boolean = false
)

data class SpiderState(
    val tableauPiles: List<TableauPile> = List(10) { TableauPile() },
    val stockGroups: List<List<Card>> = emptyList(),
    val completedSuits: Int = 0,
    val moveCount: Int = 0,
    val elapsedSeconds: Int = 0,
    val usedUndo: Boolean = false,
    val isWon: Boolean = false
)

data class FreeCellState(
    val tableauPiles: List<List<Card>> = List(8) { emptyList() },
    val freeCells: List<Card?> = List(4) { null },
    val foundations: List<List<Card>> = List(4) { emptyList() },
    val moveCount: Int = 0,
    val elapsedSeconds: Int = 0,
    val usedUndo: Boolean = false,
    val isWon: Boolean = false
)

/**
 * Pyramid solitaire. [pyramid] is 7 rows (row r has r+1 slots); a removed card
 * becomes null so positions stay stable for the triangular layout. Cards are
 * removed in pairs whose ranks sum to 13 (Ace=1 … King=13); a King (13) is
 * removed on its own. [selectedId] is the currently picked card ("pyr_r_c" or
 * "waste"). [passesRemaining] is how many more times the waste may be recycled
 * back into the stock. Winning = the whole pyramid is cleared.
 */
data class PyramidState(
    val pyramid: List<List<Card?>> = emptyList(),
    val stock: List<Card> = emptyList(),
    val waste: List<Card> = emptyList(),
    val passesRemaining: Int = 2,
    val selectedId: String? = null,
    val moveCount: Int = 0,
    val elapsedSeconds: Int = 0,
    val usedUndo: Boolean = false,
    val isWon: Boolean = false
)

data class SolitaireUiState(
    val gameMode: GameMode? = null,
    val klondike: KlondikeState? = null,
    val spider: SpiderState? = null,
    val freeCell: FreeCellState? = null,
    val pyramid: PyramidState? = null,
    val history: List<Any> = emptyList()
)
