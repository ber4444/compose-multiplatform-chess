package com.example.myapplication.persistence

import com.example.myapplication.BestMoveResult
import com.example.myapplication.ChessEngine
import com.example.myapplication.MoveClass
import com.example.myapplication.MoveRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression coverage for two bugs found while making the backfiller playerSide-aware for B6 habit
 * aggregation:
 *  - it only ever looked at even-index (White's) plies, so a BLACK-side game was backfilled against
 *    the *engine's* moves instead of the player's;
 *  - its evaluate() fallback negated a value that [ChessEngine.evaluate] already returns from
 *    White's perspective, inverting every assessment that hit it.
 */
class GameHistoryBackfillerTest {

    /** Reports a fixed White-perspective eval for every position, and a fixed "best" reply. */
    private class FakeEngine(
        private val evalCp: Int,
        private val bestMoveUci: String = "e2e4",
        private val bestMoveCp: Int = evalCp,
    ) : ChessEngine {
        override suspend fun getBestMove(fen: String, thinkTimeMs: Long?) = BestMoveResult(bestMoveUci, bestMoveCp)
        override suspend fun evaluate(fen: String, thinkTimeMs: Long?) = evalCp
        override fun close() {}
    }

    private val fenAfterDFour = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq - 0 1"
    private val fenAfterDFourNf6 = "rnbqkb1r/pppppppp/5n2/8/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 2 2"

    private fun blackToMoveGame(playerSide: String, moverIsWhite: Boolean): SavedGame {
        // FenConverter.fenToGameState is called on both fenBefore and fenAfter during backfill (for
        // motif detection), so these have to be real, parseable FENs, not placeholders — a real
        // 1.d4 / 1.d4 Nf6 pair. The target ply (Nf6) is the one left unassessed; its index (and
        // therefore whether it's the "player's" move under the fix) is controlled by prefixing the
        // d4 ply as filler when the mover should be Black.
        val filler = MoveRecord(uci = "d2d4", san = "d4", fenAfter = fenAfterDFour, cpAfter = 500)
        val target = if (moverIsWhite) {
            MoveRecord(uci = "d2d4", san = "d4", fenAfter = fenAfterDFour)
        } else {
            MoveRecord(uci = "g8f6", san = "Nf6", fenAfter = fenAfterDFourNf6)
        }
        val records = if (moverIsWhite) listOf(target) else listOf(filler, target)
        return SavedGame(
            id = "1",
            savedAtEpochMillis = 0L,
            result = "1-0",
            white = "Player",
            black = "CPU",
            moveCount = records.size,
            pgn = "",
            moveRecords = records,
            playerSide = playerSide,
        )
    }

    @Test
    fun `a WHITE-side player game backfills the White ply`() = runTest {
        val repo = GameHistoryRepository(MapSettings())
        repo.add(blackToMoveGame(playerSide = "WHITE", moverIsWhite = true))
        val backfiller = GameHistoryBackfiller(repo, FakeEngine(evalCp = 100))

        backfiller.backfillNext()

        val updated = repo.games.value.single()
        val assessed = updated.moveRecords[0]
        assertNotNull(assessed.assessment)
    }

    @Test
    fun `a BLACK-side player game backfills the Black ply not Whites filler`() = runTest {
        val repo = GameHistoryRepository(MapSettings())
        repo.add(blackToMoveGame(playerSide = "BLACK", moverIsWhite = false))
        val backfiller = GameHistoryBackfiller(repo, FakeEngine(evalCp = 100))

        backfiller.backfillNext()

        val updated = repo.games.value.single()
        // The old rule picked the first even-index (White) ply with no assessment, full stop — on
        // this BLACK-side game that's ply 0, the engine's move, not the player's. The fix must pick
        // ply 1 (Black, the player) instead and leave ply 0 alone.
        assertEquals(null, updated.moveRecords[0].assessment)
        assertNotNull(updated.moveRecords[1].assessment)
    }

    @Test
    fun `the evaluate fallback is not double-negated`() = runTest {
        // cpAfter is absent, so the backfiller must fall back to engine.evaluate(fenAfter). A White
        // eval of +900 (crushing for White) played by White must not assess as a blunder.
        val repo = GameHistoryRepository(MapSettings())
        repo.add(blackToMoveGame(playerSide = "WHITE", moverIsWhite = true))
        val backfiller = GameHistoryBackfiller(repo, FakeEngine(evalCp = 900, bestMoveCp = 900))

        backfiller.backfillNext()

        val assessment = repo.games.value.single().moveRecords[0].assessment
        assertNotNull(assessment)
        assertEquals(MoveClass.BEST, assessment.moveClass)
    }
}
