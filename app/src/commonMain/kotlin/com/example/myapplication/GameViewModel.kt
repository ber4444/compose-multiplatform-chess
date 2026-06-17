package com.example.myapplication

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GameViewModel(
    gameState: GameUiState = GameUiState()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _gameState = MutableStateFlow(gameState)
    val gameState: StateFlow<GameUiState> = _gameState

    init {
        if (_gameState.value.winState == WinState.NONE) {
            _gameState.value = applyDrawConditions(applyWinConditions(_gameState.value))
        }
    }

    private val _animState = MutableStateFlow(PieceAnimationState())
    val animState: StateFlow<PieceAnimationState> = _animState

    private val _viewState = MutableStateFlow(ViewState())
    val viewState: StateFlow<ViewState> = _viewState

    private var gameMoves: Job? = null
    private var chessEngine: ChessEngine? = null

    companion object {
        private val logger = Logger.withTag("GameViewModel")
    }

    fun attachEngine(engine: ChessEngine?) {
        chessEngine?.close()
        chessEngine = engine
    }

    fun close() {
        gameMoves?.cancel()
        chessEngine?.close()
        chessEngine = null
        scope.cancel()
    }

    fun hideWindow() {
        _viewState.value = viewState.value.copy(buttonLock = true, hideWindow = true)
    }

    data class GameViewState(
        val hideWindow: Boolean = false,
        val show3D: Boolean = false,
        val buttonLock: Boolean = false,
        val board3DUnavailable: Boolean = false
    )

    fun setShow3D(enabled: Boolean) {
        _viewState.value = viewState.value.copy(show3D = enabled, board3DUnavailable = false)
    }

    fun markBoard3DUnavailable() {
        _viewState.value = viewState.value.copy(show3D = false, board3DUnavailable = true)
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
            if (_gameState.value.drawOffer != null) return
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

    fun animationEnd() {
        if (_animState.value.pieceToAnimate == null) return
        _animState.value = _animState.value.copy(pieceToAnimate = null, secondaryPiece = null)

        if (_gameState.value.turn == Set.BLACK) {
            gameMoves?.cancel()
            gameMoves = scope.launch {
                if (!tryBlackDrawOffer()) moveBlackWithEngine()
            }
        } else {
            _viewState.value = _viewState.value.copy(moveButtonLock = false)
        }
    }

    private suspend fun moveBlackWithEngine() {
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

    fun requestDrawOffer() {
        scope.launch { offerDraw() }
    }

    suspend fun offerDraw() {
        if (!canOfferDraw(_gameState.value)) return
        _gameState.value = _gameState.value.copy(
            drawOffer = Set.WHITE,
            lastDrawOfferFullmove = _gameState.value.fullmoveNumber
        )
        val offeredState = _gameState.value
        val eval = evaluatePositionCp(chessEngine, offeredState)
        if (shouldBlackAcceptDraw(eval, offeredState)) {
            _gameState.value = _gameState.value.copy(
                winState = WinState.DRAW,
                drawOffer = null
            )
        } else {
            _gameState.value = _gameState.value.copy(
                drawOffer = null,
                drawOfferDeclinedBy = Set.BLACK
            )
        }
    }

    suspend fun tryBlackDrawOffer(): Boolean {
        val state = _gameState.value
        if (state.turn != Set.BLACK) return false
        if (!blackDrawOfferPreconditions(state)) return false
        val eval = evaluatePositionCp(chessEngine, state)
        if (shouldBlackOfferDraw(eval)) {
            _gameState.value = state.copy(
                drawOffer = Set.BLACK,
                lastDrawOfferFullmove = state.fullmoveNumber
            )
            return true
        }
        return false
    }

    fun acceptDrawOffer() {
        val state = _gameState.value
        if (state.drawOffer == Set.BLACK && state.winState == WinState.NONE) {
            _gameState.value = state.copy(drawOffer = null, winState = WinState.DRAW)
        }
    }

    fun declineDrawOffer() {
        if (_gameState.value.drawOffer == Set.BLACK) {
            _gameState.value = _gameState.value.copy(drawOffer = null)
            gameMoves?.cancel()
            gameMoves = scope.launch { moveBlackWithEngine() }
        }
    }

    fun updateUI() {
        if (_animState.value.pieceToAnimate == null) return
        _animState.value = _animState.value.copy(pieceToAnimate = null, secondaryPiece = null)

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

    suspend fun moveCPU(
        turn: Set = _gameState.value.turn,
        pickMove: suspend (
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

        if (allyPieces.isEmpty() || _gameState.value.winState != WinState.NONE) {
            return
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
        var captureOccurred = false

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
            captureOccurred = true
        } else if (allyPieces[pieceIndex] is Pawn && allyPositions[pieceIndex].second != newPosition.second && newPosition == _gameState.value.enPassantTarget) {
            val victimPosition = Pair(allyPositions[pieceIndex].first, newPosition.second)
            val index = enemyPositions.indexOf(victimPosition)
            if (index != -1) {
                logger.i { "${when (turn) { Set.WHITE -> Set.BLACK.name; Set.BLACK -> Set.WHITE.name }} ${enemyPieces[index].name} was captured en passant!" }
                mutableEnemyPositions.removeAt(index)
                mutableEnemyPieces.removeAt(index)
                captureOccurred = true
            }
        }

        val movingPiece = allyPieces[pieceIndex]
        val fromPosition = allyPositions[pieceIndex]
        
        val newHalfmoveClock = if (captureOccurred || movingPiece is Pawn) 0
                               else _gameState.value.halfmoveClock + 1
        val newFullmoveNumber = _gameState.value.fullmoveNumber + if (turn == Set.BLACK) 1 else 0
        
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

        val movedState = when (turn) {
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
                enPassantTarget = newEnPassantTarget,
                halfmoveClock = newHalfmoveClock,
                fullmoveNumber = newFullmoveNumber,
                drawOffer = null,
                drawOfferDeclinedBy = null,
                selectedSquare = INVALID_POSITION
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
                enPassantTarget = newEnPassantTarget,
                halfmoveClock = newHalfmoveClock,
                fullmoveNumber = newFullmoveNumber,
                drawOffer = null,
                drawOfferDeclinedBy = null,
                selectedSquare = INVALID_POSITION
            )
        }

        // Captures/pawn moves are irreversible: earlier positions can never recur, so reset history.
        // Otherwise lazily seed the pre-move position (covers fresh games, resetGame, and FEN-loaded
        // states without touching the constructor — some tests build GameUiState with mismatched
        // piece/position lists that must never reach positionKey).
        val priorHistory = if (newHalfmoveClock == 0) emptyList()
            else _gameState.value.positionHistory.ifEmpty { listOf(FenConverter.positionKey(_gameState.value)) }
        val newState = movedState.copy(positionHistory = priorHistory + FenConverter.positionKey(movedState))
        val winStateApplied = applyWinConditions(newState)
        if (winStateApplied.winState != WinState.NONE) return winStateApplied
        return applyDrawConditions(winStateApplied)
    }

    private fun applyWinConditions(state: GameUiState): GameUiState {
        if (state.winState != WinState.NONE) return state

        // Re-evaluate check status to be safe, especially if loaded from FEN
        val whiteKingIndex = state.piecesWhite.indexOfFirst { it is King }
        val inCheckWhite = if (whiteKingIndex != -1) {
            checkCheck(state.positionsWhite[whiteKingIndex], state.positionsBlack, state.piecesBlack, state.positionsWhite)
        } else false

        val blackKingIndex = state.piecesBlack.indexOfFirst { it is King }
        val inCheckBlack = if (blackKingIndex != -1) {
            checkCheck(state.positionsBlack[blackKingIndex], state.positionsWhite, state.piecesWhite, state.positionsBlack)
        } else false

        // Update the state with actual check statuses
        var updatedState = state.copy(inCheckWhite = inCheckWhite, inCheckBlack = inCheckBlack)

        val enemyPositions = if (updatedState.turn == Set.WHITE) updatedState.positionsBlack else updatedState.positionsWhite
        val enemyPieces = if (updatedState.turn == Set.WHITE) updatedState.piecesBlack else updatedState.piecesWhite
        val allyPositions = if (updatedState.turn == Set.WHITE) updatedState.positionsWhite else updatedState.positionsBlack
        val allyPieces = if (updatedState.turn == Set.WHITE) updatedState.piecesWhite else updatedState.piecesBlack

        val hasMoves = hasLegalMoves(enemyPositions, enemyPieces, allyPositions, allyPieces, updatedState.enPassantTarget)

        if (!hasMoves) {
            val inCheck = if (updatedState.turn == Set.WHITE) inCheckWhite else inCheckBlack
            if (inCheck) {
                updatedState = updatedState.copy(winState = if (updatedState.turn == Set.WHITE) WinState.BLACK else WinState.WHITE)
            } else {
                updatedState = updatedState.copy(winState = WinState.STALEMATE)
            }
        }
        return updatedState
    }
}
