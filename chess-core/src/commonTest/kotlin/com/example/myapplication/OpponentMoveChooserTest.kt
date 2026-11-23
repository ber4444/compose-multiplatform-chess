package com.example.myapplication

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An [OpponentMoveChooser] plays the engine side; the engine only moves when the chooser declines,
 * fails or is wrong. Opening as Black makes White the engine side, so the first move is the
 * chooser's.
 */
class OpponentMoveChooserTest {
    private class RecordingEngine : ChessEngine {
        val asked = CompletableDeferred<String>()
        override suspend fun getBestMove(fen: String, thinkTimeMs: Long?): BestMoveResult? {
            asked.complete(fen)
            return BestMoveResult("d2d4", evaluationCp = null)
        }
        override fun close() {}
    }

    private class Chooser(private val answer: suspend (List<String>) -> String?) : OpponentMoveChooser {
        override val name = "Test opponent"
        val histories = mutableListOf<List<String>>()
        override suspend fun chooseMove(fenHistory: List<String>): String? {
            histories += fenHistory
            return answer(fenHistory)
        }
    }

    private val viewModel = GameViewModel()

    @AfterTest
    fun tearDown() = viewModel.close()

    private fun onRealTime(block: suspend () -> Unit) =
        kotlinx.coroutines.test.runTest { withContext(Dispatchers.Default) { block() } }

    private suspend fun firstMove(): String {
        withTimeout(TIMEOUT_MS) { while (viewModel.gameState.value.moveHistory.isEmpty()) delay(10) }
        return viewModel.gameState.value.moveHistory.first().uci
    }

    private suspend fun open(chooser: OpponentMoveChooser, engine: RecordingEngine): String {
        viewModel.setOpponentMoveChooser(chooser)
        viewModel.attachEngine(engine)
        viewModel.playerSide = Set.BLACK
        return firstMove()
    }

    @Test
    fun chooserPlaysTheEngineSideAndTheEngineIsNotAsked() = onRealTime {
        val chooser = Chooser { "e2e4" }
        val engine = RecordingEngine()
        assertEquals("e2e4", open(chooser, engine))
        assertFalse(engine.asked.isCompleted, "the engine moved although the chooser answered")
        assertEquals(listOf(FenConverter.STARTING_FEN), chooser.histories.single())
        assertEquals("Test opponent", viewModel.opponentName)
    }

    @Test
    fun declinedIllegalOrFailedChoicesFallBackToTheEngine() {
        for (answer in listOf<suspend (List<String>) -> String?>({ null }, { "e2e5" }, { "zz" }, { error("model crashed") })) {
            val vm = GameViewModel()
            try {
                kotlinx.coroutines.test.runTest {
                    withContext(Dispatchers.Default) {
                        val engine = RecordingEngine()
                        vm.setOpponentMoveChooser(Chooser(answer))
                        vm.attachEngine(engine)
                        vm.playerSide = Set.BLACK
                        withTimeout(TIMEOUT_MS) { engine.asked.await() }
                        withTimeout(TIMEOUT_MS) { while (vm.gameState.value.moveHistory.isEmpty()) delay(10) }
                        assertEquals("d2d4", vm.gameState.value.moveHistory.first().uci)
                    }
                }
            } finally {
                vm.close()
            }
        }
    }

    @Test
    fun clearingTheChooserRestoresTheEngineAndItsName() = onRealTime {
        viewModel.setOpponentMoveChooser(Chooser { "e2e4" })
        viewModel.setOpponentMoveChooser(null)
        val engine = RecordingEngine()
        viewModel.attachEngine(engine)
        assertEquals("Stockfish", viewModel.opponentName)
        viewModel.playerSide = Set.BLACK
        withTimeout(TIMEOUT_MS) { engine.asked.await() }
        assertTrue(firstMove() == "d2d4")
    }

    /**
     * Answers Black with e7e5 (+20) and White with Nf3 (+30), and evaluates every position at -150,
     * so the two sources of the player's score give different classes: e4 graded against `evaluate`
     * is a MISTAKE (30 - -150 = 180 cp lost), and against the reply search's score it is BEST
     * (30 - 20 = 10 cp).
     */
    private class EvaluatingEngine : ChessEngine {
        val evaluated = mutableListOf<String>()
        override suspend fun getBestMove(fen: String, thinkTimeMs: Long?): BestMoveResult =
            if (" b " in fen) BestMoveResult("e7e5", evaluationCp = 20) else BestMoveResult("g1f3", evaluationCp = 30)
        override suspend fun evaluate(fen: String, thinkTimeMs: Long?): Int {
            evaluated += fen
            return -150
        }
        override fun close() {}
    }

    /** Plays e2e4 as White, lets the engine side reply, and returns the record the coach was handed. */
    private suspend fun coachedFirstMove(): MoveRecord {
        val coached = CompletableDeferred<MoveRecord>()
        viewModel.onMoveCoached = { _, record -> coached.complete(record) }
        val e2 = viewModel.gameState.value.positionsWhite.indexOf(Pair(6, 4))
        viewModel.playerMove(e2, Pair(4, 4))
        viewModel.animationEnd() // the player's animation: the engine side moves
        withTimeout(TIMEOUT_MS) {
            while (viewModel.gameState.value.moveHistory.size < 2 || viewModel.animState.value.pieceToAnimate == null) delay(10)
        }
        viewModel.animationEnd() // the reply's animation: idle analysis grades the player's move
        return withTimeout(TIMEOUT_MS) { coached.await() }
    }

    @Test
    fun theChoosersReplyStillLeavesThePlayersMoveAssessed() = onRealTime {
        // The regression: cpAfter used to come only from Stockfish's search for its reply, and
        // runIdleAnalysis skipped any move without it. With the chooser replying, every player move
        // went unassessed — no coach line and no move-quality colour on the board.
        val engine = EvaluatingEngine()
        viewModel.setOpponentMoveChooser(Chooser { "e7e5" })
        viewModel.attachEngine(engine)

        val record = coachedFirstMove()

        assertEquals("e2e4", record.uci)
        assertEquals(listOf(record.fenAfter), engine.evaluated, "the position after the player's move is the one graded")
        assertEquals(-150, record.cpAfter, "the score the assessment was graded against is saved with it")
        assertEquals(MoveClass.MISTAKE, record.assessment?.moveClass)
        assertEquals(record, viewModel.gameState.value.moveHistory.first(), "the assessment reached the game state")
    }

    @Test
    fun aScoreHarvestedFromTheEnginesReplyIsNotEvaluatedAgain() = onRealTime {
        // The Stockfish path must not pay for the fix: its reply search already scored the position.
        val engine = EvaluatingEngine()
        viewModel.attachEngine(engine)

        val record = coachedFirstMove()

        assertTrue(engine.evaluated.isEmpty(), "re-evaluated a position the reply search already scored")
        assertEquals(20, record.cpAfter)
        assertEquals(MoveClass.BEST, record.assessment?.moveClass)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
