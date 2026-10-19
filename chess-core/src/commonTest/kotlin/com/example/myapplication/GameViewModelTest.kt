package com.example.myapplication

import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.test.assertNull

class GameViewModelTest {
    private val viewModel: GameViewModel = GameViewModel()

    /** The engine-less opponent, as the production fallback wires it. */
    private val randomMover: suspend (
        List<Pair<Int, Int>>, List<Piece>, List<Pair<Int, Int>>, List<Piece>,
    ) -> SelectedMove = { theirSquares, theirPieces, ourSquares, ourPieces ->
        pickMoveCPU(theirSquares, theirPieces, ourSquares, ourPieces)
    }

    /** Every piece on the board, and no two of them on one square. */
    private fun assertBoardConsistent(state: GameUiState) {
        val occupied = state.positionsWhite + state.positionsBlack
        assertTrue(occupied.all { (row, col) -> row in 0 until BOARD_SIZE && col in 0 until BOARD_SIZE }, "piece off the board: $occupied")
        assertEquals(occupied.size, occupied.toSet().size, "two pieces share a square: $occupied")
    }

    private fun kingSquare(state: GameUiState, side: Set): Pair<Int, Int> {
        val pieces = if (side == Set.WHITE) state.piecesWhite else state.piecesBlack
        val squares = if (side == Set.WHITE) state.positionsWhite else state.positionsBlack
        return squares[pieces.indexOfFirst { it is King }]
    }

    /** Is the White king attacked in [fen]? Read straight off the position, whoever is to move. */
    private fun whiteKingAttacked(fen: String): Boolean {
        val state = FenConverter.fenToGameState(fen)
        return checkCheck(kingSquare(state, Set.WHITE), state.positionsBlack, state.piecesBlack, state.positionsWhite)
    }

    /** (side to move is in check, side to move has a legal move) for [fen]. */
    private fun checkAndMobility(fen: String): Pair<Boolean, Boolean> {
        val state = FenConverter.fenToGameState(fen)
        val white = state.turn == Set.WHITE
        val ours = if (white) state.positionsWhite else state.positionsBlack
        val ourPieces = if (white) state.piecesWhite else state.piecesBlack
        val theirs = if (white) state.positionsBlack else state.positionsWhite
        val theirPieces = if (white) state.piecesBlack else state.piecesWhite
        val inCheck = checkCheck(kingSquare(state, state.turn), theirs, theirPieces, ours)
        return inCheck to hasLegalMoves(theirs, theirPieces, ours, ourPieces, state.enPassantTarget)
    }

    @Test
    fun `a CPU move by either side keeps every piece on its own square`() = kotlinx.coroutines.test.runTest {
        viewModel.moveCPU(Set.WHITE, randomMover)
        assertBoardConsistent(viewModel.gameState.value)
        viewModel.moveCPU(Set.BLACK, randomMover)
        assertBoardConsistent(viewModel.gameState.value)
        assertEquals(2, viewModel.gameState.value.moveHistory.size, "both CPU moves should have been played")
    }

    @Test
    fun `a random game played to the end never stacks two pieces`() = kotlinx.coroutines.test.runTest {
        // Random play terminates: the fifty-move rule and insufficient material end any game that
        // mate and stalemate do not.
        while (viewModel.gameState.value.winState == WinState.NONE) {
            viewModel.moveCPU(viewModel.gameState.value.turn, randomMover)
            assertBoardConsistent(viewModel.gameState.value)
        }
    }

    // A White king on d4 walled in by its own pawns on every neighbouring square.
    private val walledKing = "3q3q/q7/7k/2PPP3/q1PKP2q/2PPP3/8/q2q2q1 w - - 0 1"

    @Test
    fun `queens on every line are blocked by the king's own wall`() {
        // Eight queens, one per line through d4, each with a white pawn in the way.
        assertFalse(whiteKingAttacked(walledKing))
    }

    @Test
    fun `a knight jumps the same wall and gives check`() {
        // b5 is a knight's move from d4; the pawns around the king do not matter to it.
        assertTrue(whiteKingAttacked("7k/8/8/1nPPP3/2PKP3/2PPP3/8/8 w - - 0 1"))
    }

    @Test
    fun `a rook check with an escape square is check but not mate`() {
        val (inCheck, canMove) = checkAndMobility("R3k3/8/8/8/8/8/8/4K3 b - - 0 1")
        assertTrue(inCheck, "the a8 rook attacks e8 along the empty back rank")
        assertTrue(canMove, "the king can step up to d7, e7 or f7")
    }

    @Test
    fun `a back-rank mate is check with no legal reply`() {
        val (inCheck, canMove) = checkAndMobility("R5k1/5ppp/8/8/8/8/8/6K1 b - - 0 1")
        assertTrue(inCheck)
        assertFalse(canMove, "f8 and h8 are covered by the rook and the pawns block the second rank")
    }

    @Test
    fun `a cornered king with no move and no check is stalemate`() {
        val (inCheck, canMove) = checkAndMobility("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertFalse(inCheck, "nothing attacks h8 itself")
        assertFalse(canMove, "g8, g7 and h7 are all covered")
    }

    @Test
    fun `test white pieces do not turn black after first CPU move`() = kotlinx.coroutines.test.runTest {
        val viewModel = GameViewModel()

        // Execute the first automatic move for white
        viewModel.moveCPU(Set.WHITE) { enemyPositions, enemyPieces, allyPositions, allyPieces ->
            pickMoveCPU(enemyPositions, enemyPieces, allyPositions, allyPieces)
        }

        // At this point, the turn should switch to BLACK
        assertTrue(viewModel.gameState.value.turn == Set.BLACK, "Turn should be BLACK")

        // Check that white pieces are still correctly stored in white arrays
        val piecesWhite = viewModel.gameState.value.piecesWhite
        for (piece in piecesWhite) {
            assertTrue(piece.set == Set.WHITE, "A white piece turned to black: ${piece.name}")
        }

        // Execute the automatic move for black to ensure black pieces stay black
        viewModel.moveCPU(Set.BLACK) { enemyPositions, enemyPieces, allyPositions, allyPieces ->
            pickMoveCPU(enemyPositions, enemyPieces, allyPositions, allyPieces)
        }

        val piecesBlack = viewModel.gameState.value.piecesBlack
        for (piece in piecesBlack) {
            assertTrue(piece.set == Set.BLACK, "A black piece turned to white: ${piece.name}")
        }

        val piecesWhiteAfterBlackMove = viewModel.gameState.value.piecesWhite
        for (piece in piecesWhiteAfterBlackMove) {
            assertTrue(piece.set == Set.WHITE, "A white piece turned to black after black's move: ${piece.name}")
        }
    }

    @Test
    fun test3DToggleUpdatesViewStateCorrectly() {
        val viewModel = GameViewModel()
        
        // Initial state
        assertTrue(viewModel.viewState.value.show3D, "Should start with 3D on")
        assertTrue(!viewModel.viewState.value.board3DUnavailable, "Unavailable flag should start false")
        
        // Turn off 3D
        viewModel.setShow3D(false)
        assertTrue(!viewModel.viewState.value.show3D, "setShow3D(false) should set show3D to false")
        assertTrue(!viewModel.viewState.value.board3DUnavailable, "board3DUnavailable should be false")
        
        // Mark unavailable
        viewModel.markBoard3DUnavailable()
        assertTrue(!viewModel.viewState.value.show3D, "markBoard3DUnavailable should turn off show3D")
        assertTrue(viewModel.viewState.value.board3DUnavailable, "markBoard3DUnavailable should set flag to true")
        
        // Turn on 3D again
        viewModel.setShow3D(true)
        assertTrue(viewModel.viewState.value.show3D, "setShow3D(true) should set show3D to true")
        assertTrue(!viewModel.viewState.value.board3DUnavailable, "setShow3D(true) should clear unavailable flag")
    }

    @Test
    fun `testStockfishMoveClearsSelectedSquare`() = kotlinx.coroutines.test.runTest {
        val viewModel = GameViewModel()

        // Simulate White selecting a piece
        viewModel.updateSelected(Pair(6, 4))

        // Simulate Black (Stockfish) making a move
        viewModel.moveCPU(Set.BLACK) { _, _, _, _ ->
            SelectedMove(position = Pair(4, 4), pieceIndex = 0)
        }

        // Verify that selectedSquare was cleared
        kotlin.test.assertEquals(INVALID_POSITION, viewModel.gameState.value.selectedSquare)
    }

    @Test
    fun `moveHistory accumulates SAN records across alternating plies`() = kotlinx.coroutines.test.runTest {
        val viewModel = GameViewModel()

        // 1.e4
        viewModel.moveCPU(Set.WHITE) { _, _, _, _ ->
            SelectedMove(Pair(4, 4), viewModel.gameState.value.positionsWhite.indexOf(Pair(6, 4)))
        }
        // 1...e5
        viewModel.moveCPU(Set.BLACK) { _, _, _, _ ->
            SelectedMove(Pair(3, 4), viewModel.gameState.value.positionsBlack.indexOf(Pair(1, 4)))
        }
        // 2.Nf3
        viewModel.moveCPU(Set.WHITE) { _, _, _, _ ->
            SelectedMove(Pair(5, 5), viewModel.gameState.value.positionsWhite.indexOf(Pair(7, 6)))
        }

        val history = viewModel.gameState.value.moveHistory
        assertEquals(3, history.size)
        assertEquals(listOf("e4", "e5", "Nf3"), history.map { it.san })
    }

    @Test
    fun `resetGame clears the move history`() = kotlinx.coroutines.test.runTest {
        val viewModel = GameViewModel()
        viewModel.moveCPU(Set.WHITE) { _, _, _, _ ->
            SelectedMove(Pair(4, 4), viewModel.gameState.value.positionsWhite.indexOf(Pair(6, 4)))
        }
        assertTrue(viewModel.gameState.value.moveHistory.isNotEmpty())

        viewModel.resetGame()
        assertTrue(viewModel.gameState.value.moveHistory.isEmpty())
    }

    // --- Phase 2: autosave + resume-later ---
    // These exercise the core's [GameSnapshotSink] seam with an in-memory test double. The real
    // russhwolf-backed `CurrentGameStore` (and its `MapSettings`-based test) lives in `:app`'s
    // androidDeviceTest (AutoSaveRestoreTest) since it depends on russhwolf.

    /** In-memory [GameSnapshotSink] capturing the last save + clear count for assertions. */
    private class RecordingSnapshotSink : GameSnapshotSink {
        var saved: GameSnapshot? = null
        var saveCount = 0
        var clearCount = 0
        override fun save(snapshot: GameSnapshot) { saved = snapshot; saveCount++ }
        override fun clear() { saved = null; clearCount++ }
    }

    @Test
    fun `playing a move triggers an autosave`() = kotlinx.coroutines.test.runTest {
        val sink = RecordingSnapshotSink()
        val viewModel = GameViewModel(snapshotSink = sink)

        viewModel.moveCPU(Set.WHITE) { _, _, _, _ ->
            SelectedMove(Pair(4, 4), viewModel.gameState.value.positionsWhite.indexOf(Pair(6, 4)))
        }

        // The autosave fired once and captured a snapshot with the expected move count.
        assertEquals(1, sink.saveCount)
        val snapshot = sink.saved
        assertEquals(1, snapshot?.moveHistory?.size)
        assertEquals(viewModel.gameState.value.turn, snapshot!!.fen.split(" ").elementAt(1).let {
            if (it == "w") Set.WHITE else Set.BLACK
        })
    }

    @Test
    fun `resetGame clears the autosave`() = kotlinx.coroutines.test.runTest {
        val sink = RecordingSnapshotSink()
        val viewModel = GameViewModel(snapshotSink = sink)

        viewModel.moveCPU(Set.WHITE) { _, _, _, _ ->
            SelectedMove(Pair(4, 4), viewModel.gameState.value.positionsWhite.indexOf(Pair(6, 4)))
        }
        assertEquals(1, sink.saveCount)

        viewModel.resetGame()
        assertEquals(1, sink.clearCount)
        assertNull(sink.saved)
    }

    @Test
    fun `a VM constructed from a restored snapshot reproduces the same gameState`() = kotlinx.coroutines.test.runTest {
        // Play two moves on VM A, autosave fires after each.
        val sink = RecordingSnapshotSink()
        val vmA = GameViewModel(snapshotSink = sink)
        vmA.moveCPU(Set.WHITE) { _, _, _, _ ->
            SelectedMove(Pair(4, 4), vmA.gameState.value.positionsWhite.indexOf(Pair(6, 4)))
        }
        vmA.moveCPU(Set.BLACK) { _, _, _, _ ->
            SelectedMove(Pair(3, 4), vmA.gameState.value.positionsBlack.indexOf(Pair(1, 4)))
        }

        // Simulate process death: rebuild a GameUiState from the last snapshot, build a fresh VM.
        val snapshot = sink.saved!!
        val restored = GameSnapshotMapper.toState(snapshot)
        val vmB = GameViewModel(restored)

        // Board + move list reproduce. Compare via FEN (lossless board) + SAN list — GameUiState's
        // auto-generated equals is identity-based on Piece instances, so it can't see that two
        // independently-built Rook(WHITE) instances are "the same".
        assertEquals(
            FenConverter.gameStateToFen(vmA.gameState.value),
            FenConverter.gameStateToFen(vmB.gameState.value),
        )
        assertEquals(
            vmA.gameState.value.moveHistory.map { it.san },
            vmB.gameState.value.moveHistory.map { it.san },
        )
        assertEquals(vmA.gameState.value.turn, vmB.gameState.value.turn)
    }

    @Test
    fun `requestHint requires attached engine and player turn and produces valid legal move hint`() = kotlinx.coroutines.test.runTest {
        val vm = GameViewModel()

        // 1. Without attached engine, requestHint does nothing
        vm.requestHint()
        assertTrue(vm.hintSquares.value.isEmpty())

        // 2. Attach a fake engine
        val fakeEngine = object : ChessEngine {
            override suspend fun configure(difficulty: EngineDifficulty) {}
            override suspend fun getBestMove(fen: String, thinkTimeMs: Long?): BestMoveResult? = BestMoveResult("e2e4", evaluationCp = 30)
            override fun close() {}
        }
        vm.attachEngine(fakeEngine)

        // 3. Request hint on player turn
        vm.computeHintDirectly()
        val hint = vm.hintSquares.value
        assertTrue(hint.isNotEmpty(), "Expected hint squares to be populated but got $hint")

        // Assert player side has legal moves
        val legalMoves = getAllLegalMoves(
            enemyPositions = vm.gameState.value.positionsBlack,
            enemyPieces = vm.gameState.value.piecesBlack,
            allyPositions = vm.gameState.value.positionsWhite,
            allyPieces = vm.gameState.value.piecesWhite,
            castlingRights = vm.gameState.value.castlingRights,
            enPassantTarget = vm.gameState.value.enPassantTarget
        )
        assertTrue(legalMoves.isNotEmpty(), "Player side should have legal moves")

        // Clear hint works
        vm.clearHint()
        assertTrue(vm.hintSquares.value.isEmpty())
    }

    /**
     * The interesting question about the hint button is not whether cancellation works — it
     * demonstrably does — but **what difficulty the engine is left at afterwards**. `requestHint()`
     * raises the engine to HARD and restores it in a `finally`; because `ChessEngine.configure` is
     * a suspend function, a cancelled hint used to abandon the restore at its first suspension
     * point and strand the opponent at Skill Level 20 for the rest of the game.
     */
    @Test
    fun `hint restores the configured difficulty even when the hint job is cancelled`() = kotlinx.coroutines.test.runTest {
        val configured = mutableListOf<EngineDifficulty>()
        val engine = object : ChessEngine {
            // A real UCI configure suspends (process write plus an `isready` round trip), which is
            // exactly what let a cancelled coroutine abandon the restore.
            override suspend fun configure(difficulty: EngineDifficulty) {
                kotlinx.coroutines.delay(10)
                configured += difficulty
            }

            override suspend fun getBestMove(fen: String, thinkTimeMs: Long?): BestMoveResult? {
                kotlinx.coroutines.delay(10_000)
                return BestMoveResult("e2e4", evaluationCp = 30)
            }

            override fun close() {}
        }
        val vm = GameViewModel(initialEngineDifficulty = EngineDifficulty.EASY)
        vm.attachEngine(engine)
        testScheduler.advanceUntilIdle()
        configured.clear()

        val hint = backgroundScope.launch { vm.computeHintDirectly() }
        // Far enough in to have raised the engine to HARD, still inside the long search.
        testScheduler.advanceTimeBy(50)
        assertEquals(listOf(EngineDifficulty.HARD), configured, "Hint should have raised the engine")

        // The impatient player taps Hint again (or leaves the screen), cancelling this job.
        hint.cancelAndJoin()

        assertEquals(
            EngineDifficulty.EASY,
            configured.lastOrNull(),
            "Engine was left at ${configured.lastOrNull()} after a cancelled hint; expected the " +
                "player's EASY setting to be restored. Sequence: $configured",
        )
    }
}