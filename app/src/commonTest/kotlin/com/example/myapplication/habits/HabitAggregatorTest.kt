package com.example.myapplication.habits

import com.example.myapplication.MoveAssessment
import com.example.myapplication.MoveClass
import com.example.myapplication.MoveRecord
import com.example.myapplication.MotifDetector
import com.example.myapplication.persistence.SavedGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HabitAggregatorTest {

    private fun assessedRecord(
        san: String = "Nc6",
        moveClass: MoveClass = MoveClass.BLUNDER,
        motifs: List<String> = listOf(MotifDetector.HANGS_PIECE),
        bestMoveSan: String? = "Nf6",
    ): MoveRecord = MoveRecord(
        uci = "b8c6",
        san = san,
        fenAfter = "8/8/8/8/8/8/8/8 w - - 0 1",
        assessment = MoveAssessment(
            cpBefore = 0,
            cpPlayed = -400,
            cpBest = 0,
            cpLoss = 400,
            moveClass = moveClass,
            motifs = motifs,
            bestMoveSan = bestMoveSan,
        ),
    )

    private fun unassessedRecord(san: String = "e4"): MoveRecord =
        MoveRecord(uci = "e2e4", san = san, fenAfter = "8/8/8/8/8/8/8/8 b - - 0 1")

    /** [playerMoves] land on even plies (0, 2, 4, ...); the odd plies are always filler. */
    private fun game(
        id: String,
        playerSide: String = "WHITE",
        playerMoves: List<MoveRecord>,
    ): SavedGame {
        val filler = unassessedRecord("a6")
        val records = playerMoves.flatMap { listOf(it, filler) }
        // BLACK-side games shift the player's moves to odd indices by prepending one filler ply.
        val ordered = if (playerSide == "BLACK") listOf(filler) + records else records
        return SavedGame(
            id = id,
            savedAtEpochMillis = 0L,
            result = "1-0",
            white = "Player",
            black = "CPU",
            moveCount = ordered.size,
            pgn = "",
            moveRecords = ordered,
            playerSide = playerSide,
        )
    }

    @Test
    fun `fewer than three considered games yields no habits`() {
        val games = listOf(
            game("1", playerMoves = listOf(assessedRecord())),
            game("2", playerMoves = listOf(assessedRecord())),
        )
        assertEquals(emptyList(), HabitAggregator.aggregate(games))
    }

    @Test
    fun `no player mistakes across games yields no habits`() {
        val clean = assessedRecord(moveClass = MoveClass.BEST, motifs = listOf(MotifDetector.DEVELOPS))
        val games = (1..5).map { game(it.toString(), playerMoves = listOf(clean)) }
        assertEquals(emptyList(), HabitAggregator.aggregate(games))
    }

    @Test
    fun `a hangs-piece mistake recurring across games is named as a habit`() {
        val hung = assessedRecord()
        val games = listOf(
            game("1", playerMoves = listOf(hung)),
            game("2", playerMoves = listOf(unassessedRecord())), // clean game, no habit here
            game("3", playerMoves = listOf(hung)),
        )
        val summaries = HabitAggregator.aggregate(games)
        assertEquals(1, summaries.size)
        assertEquals(MotifDetector.HANGS_PIECE, summaries.first().motif)
        assertEquals(2, summaries.first().gamesAffected)
        assertEquals(3, summaries.first().gamesConsidered)
    }

    @Test
    fun `a game with multiple hung pieces counts once not per occurrence`() {
        val hung = assessedRecord()
        val games = listOf(
            game("1", playerMoves = listOf(hung, hung, hung)),
            game("2", playerMoves = listOf(hung)),
            game("3", playerMoves = listOf(unassessedRecord())),
        )
        val summary = HabitAggregator.aggregate(games).single()
        assertEquals(2, summary.gamesAffected)
    }

    @Test
    fun `only BLACK-side games have the pattern respects playerSide`() {
        val hung = assessedRecord()
        // Filler is unassessed, so if the aggregator ever mistook White's filler moves for the
        // player's, these BLACK games would look clean instead of hung.
        val games = listOf(
            game("1", playerSide = "BLACK", playerMoves = listOf(hung)),
            game("2", playerSide = "BLACK", playerMoves = listOf(hung)),
            game("3", playerSide = "BLACK", playerMoves = listOf(unassessedRecord())),
        )
        val summary = HabitAggregator.aggregate(games).single()
        assertEquals(MotifDetector.HANGS_PIECE, summary.motif)
        assertEquals(2, summary.gamesAffected)
    }

    @Test
    fun `engine-side BLUNDERs on a WHITE-side player game are never counted`() {
        // The "player" moves are clean; only the filler (Black/engine) plies would look like
        // blunders if the aggregator ignored playerSide entirely.
        val engineBlunder = assessedRecord(san = "Qxh7", moveClass = MoveClass.BLUNDER)
        val clean = unassessedRecord()
        val games = (1..3).map { i ->
            val moveRecords = listOf(clean, engineBlunder) // ply 0 = player (clean), ply 1 = engine (blunder)
            SavedGame(
                id = i.toString(),
                savedAtEpochMillis = 0L,
                result = "1-0",
                white = "Player",
                black = "Stockfish",
                moveCount = moveRecords.size,
                pgn = "",
                moveRecords = moveRecords,
                playerSide = "WHITE",
            )
        }
        assertEquals(emptyList(), HabitAggregator.aggregate(games))
    }

    @Test
    fun `mistakes without a recurring costly motif fall back to the general habit`() {
        // material-swing is not in COSTLY_MOTIFS, so this never satisfies the specific-motif tier.
        val mistake = assessedRecord(moveClass = MoveClass.MISTAKE, motifs = listOf(MotifDetector.MATERIAL_SWING))
        val games = listOf(
            game("1", playerMoves = listOf(mistake)),
            game("2", playerMoves = listOf(mistake)),
            game("3", playerMoves = listOf(unassessedRecord())),
        )
        val summary = HabitAggregator.aggregate(games).single()
        assertEquals(null, summary.motif)
        assertEquals(2, summary.gamesAffected)
    }

    @Test
    fun `a single bad game is not a habit`() {
        val hung = assessedRecord()
        val games = listOf(
            game("1", playerMoves = listOf(hung, hung)),
            game("2", playerMoves = listOf(unassessedRecord())),
            game("3", playerMoves = listOf(unassessedRecord())),
        )
        assertEquals(emptyList(), HabitAggregator.aggregate(games))
    }

    @Test
    fun `window limits how many recent games are considered`() {
        val hung = assessedRecord()
        val clean = unassessedRecord()
        // Two habit games sit outside a window of 3 (they're games-ago 3 and 4); only one qualifying
        // game (the most recent) is inside the window, so the habit should not clear MIN_GAMES_FOR_HABIT.
        val games = listOf(
            game("recent1", playerMoves = listOf(hung)),
            game("recent2", playerMoves = listOf(clean)),
            game("recent3", playerMoves = listOf(clean)),
            game("old1", playerMoves = listOf(hung)),
            game("old2", playerMoves = listOf(hung)),
        )
        assertEquals(emptyList(), HabitAggregator.aggregate(games, window = 3))
        // Without the window restriction the same data does clear the bar.
        assertTrue(HabitAggregator.aggregate(games, window = 5).isNotEmpty())
    }

    @Test
    fun `occurrences carry the practice position and are capped newest first`() {
        val hung = assessedRecord()
        val games = (1..7).map { game(it.toString(), playerMoves = listOf(hung)) }
        val summary = HabitAggregator.aggregate(games).single()
        assertTrue(summary.occurrences.size <= 5)
        assertEquals(0, summary.occurrences.first().gamesAgo)
        summary.occurrences.forEach {
            assertEquals("Nc6", it.san)
            assertEquals("Nf6", it.bestMoveSan)
            assertTrue(it.fenBefore.isNotBlank())
        }
    }
}
