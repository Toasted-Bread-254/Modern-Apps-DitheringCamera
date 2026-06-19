package com.vayunmathur.games.chess.util
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.chess.R
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.data.opposite
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class GameMode {
    data object TwoPlayer : GameMode()
    data class VsAI(val playerColor: PieceColor, val difficulty: StockfishEngine.Difficulty) : GameMode()
}

data class ChessUiState(
    val board: Board = Board.initialState,
    val selectedPiece: Position? = null,
    val gameMode: GameMode = GameMode.TwoPlayer,
    val turn: PieceColor = PieceColor.WHITE,
    val gameStatus: String? = null,
    val isBoardFlipped: Boolean = false
)

class ChessViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChessUiState())
    val uiState: StateFlow<ChessUiState> = _uiState.asStateFlow()
    private val chessApi = ChessApi()

    fun onNewGame(gameMode: GameMode) {
        val isFlipped = gameMode is GameMode.VsAI && gameMode.playerColor == PieceColor.BLACK
        _uiState.value = ChessUiState(gameMode = gameMode, isBoardFlipped = isFlipped)
        if (gameMode is GameMode.VsAI) {
            StockfishEngine.difficulty = gameMode.difficulty
            if (gameMode.playerColor == PieceColor.BLACK) makeAiMove()
        }
    }

    fun onSquareClick(position: Position) {
        val state = _uiState.value
        val board = state.board
        val pieceAtPosition = board.pieces[position.row][position.col]

        if (isGameOver(board)) return

        val mode = state.gameMode
        if (mode is GameMode.VsAI && state.turn != mode.playerColor) return

        val selectedPiece = state.selectedPiece
        if (selectedPiece == null) {
            if (pieceAtPosition != null && pieceAtPosition.color == state.turn) {
                _uiState.update { it.copy(selectedPiece = position) }
            }
        } else {
            if (board.isValidMove(selectedPiece, position)) {
                val newBoard = board.movePiece(selectedPiece, position)
                _uiState.update {
                    val nextTurn = if (newBoard.promotionPosition != null) it.turn else it.turn.opposite
                    it.copy(
                        board = newBoard,
                        selectedPiece = null,
                        turn = nextTurn,
                        gameStatus = getGameStatus(newBoard, nextTurn)
                    )
                }
                if (mode is GameMode.VsAI && !isGameOver(newBoard) && newBoard.promotionPosition == null) {
                    viewModelScope.launch {
                        delay(500)
                        makeAiMove()
                    }
                }
            } else {
                _uiState.update { it.copy(selectedPiece = null) }
            }
        }
    }

    fun onPromote(pieceType: PieceType) {
        val board = _uiState.value.board
        val promotionPosition = board.promotionPosition ?: return
        val newBoard = board.promotePawn(promotionPosition, pieceType)
        _uiState.update {
            val nextTurn = it.turn.opposite
            it.copy(
                board = newBoard,
                turn = nextTurn,
                gameStatus = getGameStatus(newBoard, nextTurn)
            )
        }
        if (_uiState.value.gameMode is GameMode.VsAI && !isGameOver(newBoard)) {
            makeAiMove()
        }
    }

    private fun makeAiMove() {
        viewModelScope.launch {
            val board = _uiState.value.board
            val bestMove = chessApi.getBestMove(board)
            val newBoard = board.movePiece(bestMove.start, bestMove.end, bestMove.promotedTo)
            _uiState.update {
                val nextTurn = it.turn.opposite
                it.copy(
                    board = newBoard,
                    turn = nextTurn,
                    gameStatus = getGameStatus(newBoard, nextTurn)
                )
            }
        }
    }

    private fun isGameOver(board: Board): Boolean =
        board.isCheckmate(PieceColor.WHITE) || board.isCheckmate(PieceColor.BLACK)

    private fun getGameStatus(board: Board, turn: PieceColor): String? {
        val ctx = getApplication<Application>()
        return when {
            board.isCheckmate(turn) -> if (turn == PieceColor.WHITE) ctx.getString(R.string.black_wins) else ctx.getString(R.string.white_wins)
            board.isKingInCheck(turn) -> ctx.getString(R.string.check)
            else -> null
        }
    }
}
