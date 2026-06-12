package com.example.myapplication

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GameViewModel(
    gameState: GameUiState = GameUiState()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _gameState = MutableStateFlow(gameState)
    val gameState: StateFlow<GameUiState> = _gameState

    private val _animState = MutableStateFlow(PieceAnimationState())
    val animState: StateFlow<PieceAnimationState> = _animState

    private val _viewState = MutableStateFlow(ViewState())
    val viewState: StateFlow<ViewState> = _viewState

    private val _stockfishEnabled = MutableStateFlow(false)
    val stockfishEnabled: StateFlow<Boolean> = _stockfishEnabled

    private var gameMoves: Job? = null
    private var chessEngine: ChessEngine? = null

    companion object {
        private val logger = Logger.withTag("GameViewModel")
    }

    fun attachEngine(engine: ChessEngine?) {
        chessEngine?.close()
        chessEngine = engine
        _stockfishEnabled.value = engine != null
    }

    fun close() {
        gameMoves?.cancel()
        chessEngine?.close()
        chessEngine = null
        _stockfishEnabled.value = false
        scope.cancel()
    }

    fun setAutoPlay(newVal: Boolean) {
        _gameState.value = gameState.value.copy(autoPlay = newVal, pendingPromotion = null)
    }

    fun hideWindow() {
        _viewState.value = viewState.value.copy(buttonLock = true, hideWindow = true)
    }

    fun updateSelected(position: Pair<Int, Int>) {
        _gameState.value = gameState.value.copy(selectedSquare = position)
    }

    fun playerMoveCheck(): Boolean {
        return true
    }

    fun playerMove(selectedPieceIndex: Int, newPosition: Pair<Int, Int>) {
        if (
            gameState.value.turn == Set.WHITE &&
            _gameState.value.winState == WinState.NONE &&
            _gameState.value.piecesWhite.isNotEmpty()
        ) {
            if (_gameState.value.pendingPromotion != null) return
            if (selectedPieceIndex == -1) {
                throw IllegalStateException("Cannot identify selected Piece!")
            }

            val legalMoves = getAllLegalMoves(
                enemyPositions = gameState.value.positionsBlack,
                enemyPieces = gameState.value.piecesBlack,
                allyPositions = gameState.value.positionsWhite,
                allyPieces = gameState.value.piecesWhite,
                castlingRights = gameState.value.castlingRights,
                enPassantTarget = gameState.value.enPassantTarget
            )

            if (legalMoves.none { move -> move.first == newPosition && move.second == selectedPieceIndex }) {
                logger.w { "Cannot move into Check!" }
                return
            }

            val movingPiece = gameState.value.piecesWhite[selectedPieceIndex]
            val preMovePosition = gameState.value.positionsWhite[selectedPieceIndex]
            if (isPromotionMove(movingPiece, newPosition)) {
                _gameState.value = _gameState.value.copy(
                    pendingPromotion = PendingPromotion(
                        pieceIndex = selectedPieceIndex,
                        from = gameState.value.positionsWhite[selectedPieceIndex],
                        to = newPosition
                    )
                )
                return  // applied later by promotePawn, or discarded by cancelPromotion
            }

            _gameState.value = deriveNewGameState(
                newPosition = newPosition,
                pieceIndex = selectedPieceIndex,
                turn = gameState.value.turn,
                enemyPieces = gameState.value.piecesBlack,
                enemyPositions = gameState.value.positionsBlack,
                allyPositions = gameState.value.positionsWhite,
                allyPieces = _gameState.value.piecesWhite
            )

            val rookMove = castlingRookMove(movingPiece, preMovePosition, newPosition)

            _animState.value = PieceAnimationState(
                pieceToAnimate = gameState.value.piecesWhite[selectedPieceIndex],
                animatePositionStart = gameState.value.positionsWhite[selectedPieceIndex],
                animatePositionEnd = newPosition,
                secondaryPiece = if (rookMove != null) Rook(Set.WHITE) else null,
                secondaryStart = rookMove?.first ?: INVALID_POSITION,
                secondaryEnd = rookMove?.second ?: INVALID_POSITION
            )
        }
    }

    fun promotePawn(promotion: PromotionType) {
        val pending = _gameState.value.pendingPromotion ?: return
        if (_gameState.value.turn != Set.WHITE || _gameState.value.winState != WinState.NONE) return
        val pawn = _gameState.value.piecesWhite[pending.pieceIndex]  // capture BEFORE applying
        _gameState.value = deriveNewGameState(
            pieceIndex = pending.pieceIndex, newPosition = pending.to, turn = Set.WHITE,
            enemyPieces = _gameState.value.piecesBlack, enemyPositions = _gameState.value.positionsBlack,
            allyPositions = _gameState.value.positionsWhite, allyPieces = _gameState.value.piecesWhite,
            promotion = promotion
        )
        _animState.value = PieceAnimationState(
            pieceToAnimate = pawn, animatePositionStart = pending.from, animatePositionEnd = pending.to
        )
    }

    fun cancelPromotion() {
        _gameState.value = _gameState.value.copy(pendingPromotion = null)
    }

    fun startUserTurn() {
        logger.d { "START USER TURN" }
        if (_viewState.value.moveButtonLock) return
        logger.d { "MOVEBUTTONLOCK=TRUE" }; _viewState.value = _viewState.value.copy(moveButtonLock = true)

        gameMoves?.cancel()
        gameMoves = scope.launch {
            delay(500)
            moveCPU { enemyPositions, enemyPieces, allyPositions, allyPieces ->
                pickMoveStockfish(
                    chessEngine,
                    _gameState.value,
                    enemyPositions,
                    enemyPieces,
                    allyPositions,
                    allyPieces
                )
            }
        }
    }

    fun animationEnd() {
        if (_animState.value.pieceToAnimate == null) return
        _animState.value = _animState.value.copy(pieceToAnimate = null)

        if (_gameState.value.turn == Set.BLACK) {
            moveCPU { enemyPositions, enemyPieces, allyPositions, allyPieces ->
                pickMoveStockfish(
                    chessEngine,
                    _gameState.value,
                    enemyPositions,
                    enemyPieces,
                    allyPositions,
                    allyPieces
                )
            }
        } else {
            _viewState.value = _viewState.value.copy(moveButtonLock = false)
        }
    }

    fun updateUI() {
        if (_animState.value.pieceToAnimate == null) return
        _animState.value = _animState.value.copy(pieceToAnimate = null)

        if (_gameState.value.turn == Set.WHITE) {
            _viewState.value = _viewState.value.copy(moveButtonLock = false)
        }
    }

    fun resetGame() {
        logger.i { "Game reset" }
        _gameState.value = GameUiState()
        _viewState.value = ViewState()
        _animState.value = PieceAnimationState()
    }

    fun moveCPU(
        turn: Set = _gameState.value.turn,
        pickMove: (
            enemyPositions: List<Pair<Int, Int>>,
            enemyPieces: List<Piece>,
            allyPositions: List<Pair<Int, Int>>,
            allyPieces: List<Piece>
        ) -> SelectedMove
    ) {
        _gameState.value = _gameState.value.copy(turn = turn, selectedSquare = INVALID_POSITION)
        logger.d { "MOVEBUTTONLOCK=TRUE" }; _viewState.value = _viewState.value.copy(moveButtonLock = true)

        val allyPositions: List<Pair<Int, Int>>
        val allyPieces: List<Piece>
        val enemyPositions: List<Pair<Int, Int>>
        val enemyPieces: List<Piece>
        when (turn) {
            Set.WHITE -> {
                allyPositions = _gameState.value.positionsWhite
                allyPieces = _gameState.value.piecesWhite
                enemyPositions = _gameState.value.positionsBlack
                enemyPieces = _gameState.value.piecesBlack
            }

            Set.BLACK -> {
                allyPositions = _gameState.value.positionsBlack
                allyPieces = _gameState.value.piecesBlack
                enemyPositions = _gameState.value.positionsWhite
                enemyPieces = _gameState.value.piecesWhite
            }
        }

        if (allyPieces.isEmpty() || _gameState.value.winState != WinState.NONE) {
            return
        }

        if ((_gameState.value.inCheckWhite && _gameState.value.turn != Set.WHITE) ||
            (_gameState.value.inCheckBlack && _gameState.value.turn != Set.BLACK)
        ) {
            _gameState.value = _gameState.value.copy(
                winState = if (_gameState.value.turn == Set.WHITE) WinState.WHITE else WinState.BLACK
            )
            return
        }

        if ((_gameState.value.inCheckWhite && _gameState.value.turn == Set.WHITE) ||
            (_gameState.value.inCheckBlack && _gameState.value.turn == Set.BLACK)
        ) {
            if (hasLegalMoves(enemyPositions, enemyPieces, allyPositions, allyPieces, _gameState.value.enPassantTarget)) {
                logger.i { "Must escape check!" }
            } else {
                logger.i { "No legal moves to escape check! You lose!" }
                _gameState.value = _gameState.value.copy(
                    winState = if (_gameState.value.turn == Set.BLACK) WinState.WHITE else WinState.BLACK
                )
                return
            }
        } else if ((!_gameState.value.inCheckWhite && _gameState.value.turn == Set.WHITE) ||
            (!_gameState.value.inCheckBlack && _gameState.value.turn == Set.BLACK)
        ) {
            if (hasLegalMoves(enemyPositions, enemyPieces, allyPositions, allyPieces, _gameState.value.enPassantTarget)) {
                logger.d { "Continue playing, legal moves available." }
            } else {
                logger.i { "No legal moves available, Stalemate!" }
                _gameState.value = _gameState.value.copy(winState = WinState.STALEMATE)
                return
            }
        }

        val selectedMove = pickMove(enemyPositions, enemyPieces, allyPositions, allyPieces)
        val newPosition = selectedMove.position
        val movingPiece = allyPieces[selectedMove.pieceIndex]
        val preMovePosition = allyPositions[selectedMove.pieceIndex]

        _gameState.value = deriveNewGameState(
            newPosition = newPosition,
            pieceIndex = selectedMove.pieceIndex,
            turn = turn,
            enemyPieces = enemyPieces,
            enemyPositions = enemyPositions,
            allyPositions = allyPositions,
            allyPieces = allyPieces,
            promotion = selectedMove.promotion
        )

        val rookMove = castlingRookMove(movingPiece, preMovePosition, newPosition)

        _animState.value = PieceAnimationState(
            pieceToAnimate = allyPieces[selectedMove.pieceIndex],
            animatePositionStart = allyPositions[selectedMove.pieceIndex],
            animatePositionEnd = selectedMove.position,
            secondaryPiece = if (rookMove != null) Rook(turn) else null,
            secondaryStart = rookMove?.first ?: INVALID_POSITION,
            secondaryEnd = rookMove?.second ?: INVALID_POSITION
        )
    }

    private fun deriveNewGameState(
        pieceIndex: Int,
        newPosition: Pair<Int, Int>,
        turn: Set,
        enemyPieces: List<Piece>,
        enemyPositions: List<Pair<Int, Int>>,
        allyPositions: List<Pair<Int, Int>>,
        allyPieces: List<Piece>,
        promotion: PromotionType? = null
    ): GameUiState {
        val mutableEnemyPieces = enemyPieces.toMutableList()
        val mutableEnemyPositions = enemyPositions.toMutableList()
        val mutableAllyPositions = allyPositions.toMutableList()
        val mutableAllyPieces = allyPieces.toMutableList()

        logger.d { "Moving $turn ${allyPieces[pieceIndex].name} from ${allyPositions[pieceIndex]} to $newPosition" }

        var updatedRights = _gameState.value.castlingRights

        if (newPosition in enemyPositions) {
            val index = enemyPositions.indexOf(newPosition)
            logger.i { "${when (turn) { Set.WHITE -> Set.BLACK.name; Set.BLACK -> Set.WHITE.name }} ${enemyPieces[index].name} was captured!" }
            
            val capturedPiece = enemyPieces[index]
            if (capturedPiece is Rook) {
                if (turn == Set.WHITE) {
                    if (newPosition == BLACK_KS_ROOK_HOME) updatedRights = updatedRights.copy(blackKingside = false)
                    if (newPosition == BLACK_QS_ROOK_HOME) updatedRights = updatedRights.copy(blackQueenside = false)
                } else {
                    if (newPosition == WHITE_KS_ROOK_HOME) updatedRights = updatedRights.copy(whiteKingside = false)
                    if (newPosition == WHITE_QS_ROOK_HOME) updatedRights = updatedRights.copy(whiteQueenside = false)
                }
            }

            mutableEnemyPositions.removeAt(index)
            mutableEnemyPieces.removeAt(index)
        } else if (allyPieces[pieceIndex] is Pawn && allyPositions[pieceIndex].second != newPosition.second && newPosition == _gameState.value.enPassantTarget) {
            val victimPosition = Pair(allyPositions[pieceIndex].first, newPosition.second)
            val index = enemyPositions.indexOf(victimPosition)
            if (index != -1) {
                logger.i { "${when (turn) { Set.WHITE -> Set.BLACK.name; Set.BLACK -> Set.WHITE.name }} ${enemyPieces[index].name} was captured en passant!" }
                mutableEnemyPositions.removeAt(index)
                mutableEnemyPieces.removeAt(index)
            }
        }

        val movingPiece = allyPieces[pieceIndex]
        val fromPosition = allyPositions[pieceIndex]
        
        val newEnPassantTarget = if (movingPiece is Pawn && kotlin.math.abs(newPosition.first - fromPosition.first) == 2) {
            Pair((newPosition.first + fromPosition.first) / 2, fromPosition.second)
        } else {
            null
        }
        
        if (movingPiece is King) {
            if (turn == Set.WHITE) {
                updatedRights = updatedRights.copy(whiteKingside = false, whiteQueenside = false)
            } else {
                updatedRights = updatedRights.copy(blackKingside = false, blackQueenside = false)
            }
        } else if (movingPiece is Rook) {
            if (turn == Set.WHITE) {
                if (fromPosition == WHITE_KS_ROOK_HOME) updatedRights = updatedRights.copy(whiteKingside = false)
                if (fromPosition == WHITE_QS_ROOK_HOME) updatedRights = updatedRights.copy(whiteQueenside = false)
            } else {
                if (fromPosition == BLACK_KS_ROOK_HOME) updatedRights = updatedRights.copy(blackKingside = false)
                if (fromPosition == BLACK_QS_ROOK_HOME) updatedRights = updatedRights.copy(blackQueenside = false)
            }
        }

        mutableAllyPositions[pieceIndex] = newPosition
        
        castlingRookMove(movingPiece, fromPosition, newPosition)?.let { (rookFrom, rookTo) ->
            val rookIndex = allyPositions.indexOf(rookFrom)
            if (rookIndex != -1) {
                mutableAllyPositions[rookIndex] = rookTo
            }
        }
        if (isPromotionMove(allyPieces[pieceIndex], newPosition)) {
            val promoted = (promotion ?: PromotionType.QUEEN).toPiece(turn)
            logger.i { "$turn Pawn promoted to ${promoted.name}!" }
            mutableAllyPieces[pieceIndex] = promoted
        }

        val allyKingIndex = mutableAllyPieces.indexOfFirst { it::class == King::class }
        val allyInCheck = checkCheck(
            mutableAllyPositions[allyKingIndex],
            mutableEnemyPositions,
            mutableEnemyPieces,
            mutableAllyPositions
        )

        val enemyKingIndex = mutableEnemyPieces.indexOfFirst { it::class == King::class }
        val enemyInCheck = checkCheck(
            mutableEnemyPositions[enemyKingIndex],
            mutableAllyPositions,
            mutableAllyPieces,
            mutableEnemyPositions
        )

        val nextTurn = when (_gameState.value.turn) {
            Set.WHITE -> Set.BLACK
            Set.BLACK -> Set.WHITE
        }

        if (allyInCheck) {
            logger.i { "Ally $turn in Check!" }
        } else if (enemyInCheck) {
            logger.i { "Enemy $nextTurn in Check!" }
        }

        return when (turn) {
            Set.WHITE -> _gameState.value.copy(
                turn = nextTurn,
                piecesBlack = mutableEnemyPieces,
                positionsBlack = mutableEnemyPositions,
                positionsWhite = mutableAllyPositions,
                piecesWhite = mutableAllyPieces,
                inCheckWhite = allyInCheck,
                inCheckBlack = enemyInCheck,
                pendingPromotion = null,
                castlingRights = updatedRights,
                enPassantTarget = newEnPassantTarget
            )

            Set.BLACK -> _gameState.value.copy(
                turn = nextTurn,
                piecesWhite = mutableEnemyPieces,
                positionsWhite = mutableEnemyPositions,
                positionsBlack = mutableAllyPositions,
                piecesBlack = mutableAllyPieces,
                inCheckWhite = enemyInCheck,
                inCheckBlack = allyInCheck,
                pendingPromotion = null,
                castlingRights = updatedRights,
                enPassantTarget = newEnPassantTarget
            )
        }
    }
}
