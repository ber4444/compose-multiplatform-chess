package com.example.myapplication

import co.touchlab.kermit.Logger
import com.example.myapplication.GameSnapshotMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class GameViewModel(
    gameState: GameUiState = GameUiState(),
    private val snapshotSink: GameSnapshotSink? = null,
    initialShow3D: Boolean = true,
    initialEngineDifficulty: EngineDifficulty = EngineDifficulty.MEDIUM,
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

    // Seeded from the persisted AppSettings.board3DEnabled at construction (entry points pass it in).
    // GameScreen re-runs its 3D entry/teardown choreography whenever AppSettings.board3DEnabled flips.
    private val _viewState = MutableStateFlow(ViewState(show3D = initialShow3D))
    val viewState: StateFlow<ViewState> = _viewState

    private val _hintText = MutableStateFlow<String?>(null)
    val hintText: StateFlow<String?> = _hintText
    private var hintJob: Job? = null

    /**
     * Guards the engine's *global* skill level, which is process-wide state on a single UCI
     * process: `setoption name Skill Level` applies to every subsequent search, not to one call.
     * `BaseStockfishEngine`'s own mutex serializes individual UCI exchanges but knows nothing about
     * this, so without the lock a hint (which raises the engine to HARD) overlapping the idle
     * analysis makes `evaluatePositionCp` return a full-strength evaluation that is then compared
     * against a weak-engine move — producing the wrong quality bracket on the coached move.
     */
    private val engineStrengthMutex = Mutex()

    /**
     * Computes a full-strength Stockfish move hint for the player's turn (B5).
     * Requires an attached engine, guards turn state, and cancels previous hint requests.
     */
    fun requestHint() {
        hintJob?.cancel()
        hintJob = scope.launch { computeHintDirectly() }
    }

    suspend fun computeHintDirectly(): String? {
        val current = _gameState.value
        if (current.turn != playerSide || current.winState != WinState.NONE) return null
        val engine = chessEngine ?: return null

        val allyPositions = if (current.turn == Set.WHITE) current.positionsWhite else current.positionsBlack
        val allyPieces = if (current.turn == Set.WHITE) current.piecesWhite else current.piecesBlack
        val enemyPositions = if (current.turn == Set.WHITE) current.positionsBlack else current.positionsWhite
        val enemyPieces = if (current.turn == Set.WHITE) current.piecesBlack else current.piecesWhite

        return engineStrengthMutex.withLock {
            try {
                engine.configure(EngineDifficulty.HARD)
                val move = pickMoveStockfish(engine, current, enemyPositions, enemyPieces, allyPositions, allyPieces)
                val hint = formatHint(current, move, allyPositions, allyPieces, enemyPositions)
                _hintText.value = hint
                hint
            } finally {
                // NonCancellable is load-bearing. `requestHint()` cancels the previous hintJob on
                // every tap, and `ChessEngine.configure` is a *suspend* function: in a cancelled
                // coroutine it throws CancellationException at its first suspension point, so the
                // restore never reaches Stockfish. The engine would then stay at Skill Level 20 for
                // the rest of the game — a player on Easy silently plays a full-strength opponent,
                // with no error and nothing in the UI to explain it.
                withContext(NonCancellable) { engine.configure(engineDifficulty) }
            }
        }
    }

    private fun formatHint(
        current: GameUiState,
        move: SelectedMove,
        allyPositions: List<Pair<Int, Int>>,
        allyPieces: List<Piece>,
        enemyPositions: List<Pair<Int, Int>>,
    ): String {
        if (move.position != INVALID_POSITION && move.pieceIndex != -1) {
            val from = allyPositions[move.pieceIndex]
            val to = move.position
            val movingPiece = allyPieces[move.pieceIndex]
            val isCapture = enemyPositions.contains(to) || (to == current.enPassantTarget && movingPiece is Pawn)
            val san = SanConverter.toSan(
                preMove = current,
                pieceIndex = move.pieceIndex,
                from = from,
                to = to,
                movingPiece = movingPiece,
                isCapture = isCapture,
                promotion = move.promotion,
                castleRook = castlingRookMove(movingPiece, from, to),
                // Deliberately no "+"/"#": the real move path derives those from the *post-move*
                // state (see deriveNewGameState), and speculatively applying the candidate move
                // just to decorate a hint would run the autosave/move-record side effects too.
                // A hint reads fine as "Try Qh5"; it does not need to announce mate.
                checkSuffix = "",
            )
            return "Hint: Try $san"
        } else {
            return "Hint: No legal moves available"
        }
    }

    fun clearHint() {
        _hintText.value = null
    }

    private var gameMoves: Job? = null
    private var chessEngine: ChessEngine? = null

    /** Current engine difficulty (issue #39 Phase 4). Applied to the engine on attach + on change. */
    private var engineDifficulty: EngineDifficulty = initialEngineDifficulty

    /** The side the player is playing as. Defaults to WHITE. (issue #39 Phase 4). */
    var playerSide: Set = Set.WHITE

    /** `true` when a real engine (Stockfish) drives the opponent; `false` = built-in CPU fallback.
     *  Used for PGN player naming (issue #39 Phase 3: Black = "Stockfish" vs "CPU"). */
    val engineAttached: Boolean get() = chessEngine != null

    companion object {
        private val logger = Logger.withTag("GameViewModel")
    }

    fun attachEngine(engine: ChessEngine?) {
        chessEngine?.close()
        chessEngine = engine
        // Apply the current difficulty to the new engine (issue #39 Phase 4). The default no-op
        // configure() leaves the CPU fallback unaffected; real engines send the setoption.
        applyDifficulty()
        // Resume-later: if the game was restored from autosave and it's the engine's move, nudge the
        // turn-driven engine flow (mirrors `animationEnd()`'s engine branch). Skipped when there's
        // no engine (CPU fallback resumes lazily via `moveCPU`) or the game is already over.
        maybeResumeEngine()
    }

    /** Updates the engine difficulty and applies it to the attached engine (issue #39 Phase 4). */
    fun setEngineDifficulty(difficulty: EngineDifficulty) {
        engineDifficulty = difficulty
        applyDifficulty()
    }

    private fun applyDifficulty() {
        val engine = chessEngine ?: return
        scope.launch { engine.configure(engineDifficulty) }
    }

    val engineSide: Set get() = if (playerSide == Set.WHITE) Set.BLACK else Set.WHITE

    private fun maybeResumeEngine() {
        if (chessEngine == null) return
        if (_gameState.value.turn != engineSide) return
        if (_gameState.value.winState != WinState.NONE) return
        if (_animState.value.pieceToAnimate != null) return  // don't race an in-flight animation
        gameMoves?.cancel()
        gameMoves = scope.launch {
            if (!tryEngineDrawOffer()) moveEngine()
        }
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
            gameState.value.turn == playerSide &&
            _gameState.value.winState == WinState.NONE &&
            (if (playerSide == Set.WHITE) _gameState.value.piecesWhite else _gameState.value.piecesBlack).isNotEmpty()
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

            analysisJob?.cancel()
            val movingPiece = if (playerSide == Set.WHITE) gameState.value.piecesWhite[selectedPieceIndex] else gameState.value.piecesBlack[selectedPieceIndex]
            val preMovePosition = if (playerSide == Set.WHITE) gameState.value.positionsWhite[selectedPieceIndex] else gameState.value.positionsBlack[selectedPieceIndex]
            if (isPromotionMove(movingPiece, newPosition)) {
                _gameState.value = _gameState.value.copy(
                    pendingPromotion = PendingPromotion(
                        pieceIndex = selectedPieceIndex,
                        from = preMovePosition,
                        to = newPosition
                    )
                )
                return  // applied later by promotePawn, or discarded by cancelPromotion
            }

            _gameState.value = deriveNewGameState(
                newPosition = newPosition,
                pieceIndex = selectedPieceIndex,
                turn = gameState.value.turn,
                enemyPieces = if (playerSide == Set.WHITE) gameState.value.piecesBlack else gameState.value.piecesWhite,
                enemyPositions = if (playerSide == Set.WHITE) gameState.value.positionsBlack else gameState.value.positionsWhite,
                allyPositions = if (playerSide == Set.WHITE) gameState.value.positionsWhite else gameState.value.positionsBlack,
                allyPieces = if (playerSide == Set.WHITE) gameState.value.piecesWhite else gameState.value.piecesBlack
            )
            autosave()

            val rookMove = castlingRookMove(movingPiece, preMovePosition, newPosition)

            if (_gameState.value.winState == WinState.NONE) {
                // Coach is triggered asynchronously in runIdleAnalysis after engine replies
            }

            _animState.value = PieceAnimationState(
                pieceToAnimate = movingPiece,
                animatePositionStart = preMovePosition,
                animatePositionEnd = newPosition,
                secondaryPiece = if (rookMove != null) Rook(playerSide) else null,
                secondaryStart = rookMove?.first ?: INVALID_POSITION,
                secondaryEnd = rookMove?.second ?: INVALID_POSITION
            )
        }
    }

    fun promotePawn(promotion: PromotionType) {
        val pending = _gameState.value.pendingPromotion ?: return
        if (_gameState.value.turn != playerSide || _gameState.value.winState != WinState.NONE) return
        val pawn = if (playerSide == Set.WHITE) _gameState.value.piecesWhite[pending.pieceIndex] else _gameState.value.piecesBlack[pending.pieceIndex]
        _gameState.value = deriveNewGameState(
            pieceIndex = pending.pieceIndex, newPosition = pending.to, turn = playerSide,
            enemyPieces = if (playerSide == Set.WHITE) _gameState.value.piecesBlack else _gameState.value.piecesWhite, 
            enemyPositions = if (playerSide == Set.WHITE) _gameState.value.positionsBlack else _gameState.value.positionsWhite,
            allyPositions = if (playerSide == Set.WHITE) _gameState.value.positionsWhite else _gameState.value.positionsBlack, 
            allyPieces = if (playerSide == Set.WHITE) _gameState.value.piecesWhite else _gameState.value.piecesBlack,
            promotion = promotion
        )
        autosave()

        if (_gameState.value.winState == WinState.NONE) {
            // Coach is triggered asynchronously in runIdleAnalysis after engine replies
        }

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

        if (_gameState.value.turn == engineSide) {
            gameMoves?.cancel()
            gameMoves = scope.launch {
                if (!tryEngineDrawOffer()) moveEngine()
            }
        } else {
            _viewState.value = _viewState.value.copy(moveButtonLock = false)
            
            analysisJob?.cancel()
            analysisJob = scope.launch {
                runIdleAnalysis()
            }
        }
    }

    private suspend fun moveEngine() {
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
        if (!canOfferDraw(_gameState.value, playerSide)) return
        _gameState.value = _gameState.value.copy(
            drawOffer = playerSide,
            lastDrawOfferFullmove = _gameState.value.fullmoveNumber
        )
        val offeredState = _gameState.value
        val eval = evaluatePositionCp(chessEngine, offeredState)
        // Flip the eval logic depending on the engine's side
        val shouldEngineAccept = if (engineSide == Set.BLACK) shouldBlackAcceptDraw(eval, offeredState) else shouldWhiteAcceptDraw(eval, offeredState)
        if (shouldEngineAccept) {
            _gameState.value = _gameState.value.copy(
                winState = WinState.DRAW,
                drawOffer = null
            )
            autosave()  // terminal: persist the agreed draw so resume shows it (or clears on reload)
        } else {
            _gameState.value = _gameState.value.copy(
                drawOffer = null,
                drawOfferDeclinedBy = engineSide
            )
        }
    }

    suspend fun tryEngineDrawOffer(): Boolean {
        val state = _gameState.value
        if (state.turn != engineSide) return false
        val preconditions = if (engineSide == Set.BLACK) blackDrawOfferPreconditions(state) else whiteDrawOfferPreconditions(state)
        if (!preconditions) return false
        val eval = evaluatePositionCp(chessEngine, state)
        val shouldOffer = if (engineSide == Set.BLACK) shouldBlackOfferDraw(eval) else shouldWhiteOfferDraw(eval)
        if (shouldOffer) {
            _gameState.value = state.copy(
                drawOffer = engineSide,
                lastDrawOfferFullmove = state.fullmoveNumber
            )
            return true
        }
        return false
    }

    fun acceptDrawOffer() {
        val state = _gameState.value
        if (state.drawOffer == engineSide && state.winState == WinState.NONE) {
            _gameState.value = state.copy(drawOffer = null, winState = WinState.DRAW)
            autosave()  // terminal draw: persist so resume reflects it
        }
    }

    fun declineDrawOffer() {
        if (_gameState.value.drawOffer == engineSide) {
            _gameState.value = _gameState.value.copy(drawOffer = null)
            gameMoves?.cancel()
            gameMoves = scope.launch { moveEngine() }
        }
    }

    fun updateUI() {
        if (_animState.value.pieceToAnimate == null) return
        _animState.value = _animState.value.copy(pieceToAnimate = null, secondaryPiece = null)

        if (_gameState.value.turn == playerSide) {
            _viewState.value = _viewState.value.copy(moveButtonLock = false)
        }
    }

    fun resetGame(show3D: Boolean = viewState.value.show3D) {
        logger.i { "Game reset" }
        _gameState.value = GameUiState()
        _viewState.value = ViewState(show3D = show3D)
        _animState.value = PieceAnimationState()
        // The fresh empty game replaces the autosaved one; drop the stale snapshot so a relaunch
        // doesn't restore into a board the user already abandoned.
        snapshotSink?.clear()
    }

    /**
     * Writes the current game to [snapshotSink] (if configured). Called explicitly at move /
     * draw-resolution boundaries — **not** on transient `selectedSquare` updates — so the autosave
     * reflects positions a user would actually want to resume from. Gated on a non-empty board so
     * the very first save of a brand-new game (before any move) is skipped.
     */
    private fun autosave() {
        val sink = snapshotSink ?: return
        val state = _gameState.value
        if (state.positionsWhite.isEmpty() && state.positionsBlack.isEmpty()) return
        runCatching {
            sink.save(GameSnapshotMapper.fromState(state))
        }.onFailure { logger.w(it) { "Autosave failed" } }
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

        // Snapshot the pre-move state
        val stateBefore = _gameState.value

        val selectedMove = pickMove(enemyPositions, enemyPieces, allyPositions, allyPieces)
        val newPosition = selectedMove.position
        val movingPiece = allyPieces[selectedMove.pieceIndex]
        val preMovePosition = allyPositions[selectedMove.pieceIndex]

        // Update previous move's cpAfter if available (harvested from engine search)
        if (selectedMove.evaluationCp != null && _gameState.value.moveHistory.isNotEmpty()) {
            val updatedHistory = _gameState.value.moveHistory.toMutableList()
            val lastIndex = updatedHistory.lastIndex
            updatedHistory[lastIndex] = updatedHistory[lastIndex].copy(cpAfter = selectedMove.evaluationCp)
            _gameState.value = _gameState.value.copy(moveHistory = updatedHistory)
        }

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
        autosave()

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

    @Suppress("UNUSED_PARAMETER")
    // turn/enemy/ally params are retained for the existing call sites in playerMove / moveCPU /
    // promotePawn; the pure transition now lives in the top-level applyMove (Move.kt), which
    // derives the ally/enemy split from _gameState.value.turn. Keeping this as a thin delegate
    // preserves every existing call without touching UI/animation behavior.
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
        val state = _gameState.value
        val movedState = applyMove(state, pieceIndex, newPosition, promotion)
        
        val priorHistory = if (movedState.halfmoveClock == 0) emptyList()
            else state.positionHistory.ifEmpty { listOf(FenConverter.positionKey(state)) }
        val newState = movedState.copy(positionHistory = priorHistory + FenConverter.positionKey(movedState))
        val winStateApplied = applyWinConditions(newState)
        val finalState = if (winStateApplied.winState != WinState.NONE) winStateApplied
                         else applyDrawConditions(winStateApplied)

        // Append a MoveRecord for PGN/history. Built last so SAN's `#`/`+` suffix can use the
        // post-move win/check evaluation. preMove = _gameState.value (the caller has not yet
        // published the new state).
        val preMove = _gameState.value
        val enemyInCheck = if (preMove.turn == Set.WHITE) finalState.inCheckBlack else finalState.inCheckWhite
        val checkSuffix = when {
            finalState.winState == WinState.WHITE || finalState.winState == WinState.BLACK -> "#"
            enemyInCheck -> "+"
            else -> ""
        }
        
        val movingPiece = allyPieces[pieceIndex]
        val fromPosition = allyPositions[pieceIndex]
        val captureOccurred = newPosition in enemyPositions || 
            (movingPiece is Pawn && fromPosition.second != newPosition.second && newPosition == preMove.enPassantTarget)
        val castleRook = castlingRookMove(movingPiece, fromPosition, newPosition)

        val record = MoveRecord(
            uci = UciMoveConverter.appMoveToUci(fromPosition, newPosition) +
                (promotion?.uciChar?.toString() ?: ""),
            san = SanConverter.toSan(
                preMove = preMove,
                pieceIndex = pieceIndex,
                from = fromPosition,
                to = newPosition,
                movingPiece = movingPiece,
                isCapture = captureOccurred,
                promotion = promotion,
                castleRook = castleRook,
                checkSuffix = checkSuffix,
            ),
            fenAfter = FenConverter.gameStateToFen(finalState),
        )
        return finalState.copy(moveHistory = preMove.moveHistory + record)
    }

    private var engineJob: Job? = null
    var aiCoachEnabled: Boolean = true

    var onMoveCoached: ((fenBefore: String, moveRecord: MoveRecord) -> Unit)? = null

    private var analysisJob: Job? = null

    private suspend fun runIdleAnalysis() {
        val engine = chessEngine ?: return
        val state = _gameState.value
        val history = state.moveHistory
        if (history.isEmpty()) return

        // Look for the most recent unassessed player move.
        // If it's the player's turn, the history ends with the engine's move.
        // Player's last move is at lastIndex - 1.
        val targetIndex = history.indexOfLast { 
            val isPlayerMove = if (playerSide == Set.WHITE) history.indexOf(it) % 2 == 0 else history.indexOf(it) % 2 != 0
            isPlayerMove && it.assessment == null && it.cpAfter != null
        }
        
        if (targetIndex == -1) return

        val playerRecord = history[targetIndex]
        
        // FEN before player's move
        val fenBefore = if (targetIndex == 0) {
            FenConverter.gameStateToFen(GameUiState()) // Initial state
        } else {
            history[targetIndex - 1].fenAfter
        }

        val cpPlayed = playerRecord.cpAfter ?: return

        // Expensive call behind Mutex
        // Bound cpBest by movetime, not depth, as requested in RAG-1 B1a.4
        val stateBefore = FenConverter.fenToGameState(fenBefore)
        // Held across the evaluation so a concurrent hint can't leave the engine at HARD underneath
        // it — see engineStrengthMutex.
        // One search does both jobs: the score it reports *is* the eval of the best move, and its
        // UCI is what the coach needs to name the alternative. Both are already normalized to
        // White's perspective by every transport, matching what evaluate() returns.
        val bestMoveResult = engineStrengthMutex.withLock {
            engine.getBestMove(fenBefore, engineDifficulty.thinkTimeMs)
        }
        // A null result, or a search that reported no score, must not cost the move its assessment
        // — evaluatePositionCp still answers, degrading to material balance with no engine. Losing
        // the assessment loses the coach line for that ply, which is the whole feature.
        val cpBest = bestMoveResult?.evaluationCp ?: engineStrengthMutex.withLock {
            evaluatePositionCp(engine, stateBefore, engineDifficulty.thinkTimeMs)
        }

        // Motif detection (fast)
        val stateBeforeObj = FenConverter.fenToGameState(fenBefore)
        val toSquare = UciMoveConverter.parseUciMove(playerRecord.uci).second
        val movingSide = if (targetIndex % 2 == 0) Set.WHITE else Set.BLACK
        val stateAfterObj = FenConverter.fenToGameState(playerRecord.fenAfter)
        val motifs = MotifDetector.detectMotifs(stateBeforeObj, stateAfterObj, movingSide, toSquare)

        val assessment = MoveAssessor.assessMove(
            cpBefore = cpBest, // By definition, eval of board before move is the eval of the best move
            cpPlayed = cpPlayed,
            cpBest = cpBest,
            motifs = motifs,
            bestMoveUci = bestMoveResult?.uci,
        )

        // Update history safely
        val currentHistory = _gameState.value.moveHistory.toMutableList()
        // Ensure we haven't lost sync
        if (targetIndex < currentHistory.size && currentHistory[targetIndex].uci == playerRecord.uci) {
            val updatedRecord = playerRecord.copy(assessment = assessment)
            currentHistory[targetIndex] = updatedRecord
            _gameState.value = _gameState.value.copy(moveHistory = currentHistory)
            autosave()
            
            if (_gameState.value.winState == WinState.NONE) {
                onMoveCoached?.invoke(fenBefore, updatedRecord)
            }
        }
    }
}
