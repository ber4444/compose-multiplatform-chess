package com.example.myapplication.explorer

import com.example.myapplication.FenConverter
import com.example.myapplication.MoveRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the Kotlin port against the reference Python encoder via `MaiaGolden`. */
class MaiaEncoderTest {
    @Test
    fun tokensMatchReferenceEncoder() {
        for (case in MaiaGolden.cases) {
            val tokens = MaiaEncoder.tokens(history(case))
            val nonZero = tokens.indices.filter { tokens[it] != 0f }
            assertEquals(case.tokenIndices.toList(), nonZero, case.name)
            assertTrue(nonZero.all { tokens[it] == 1f }, case.name)
        }
    }

    @Test
    fun legalMoveIndicesMatchReferenceMask() {
        for (case in MaiaGolden.cases) {
            val fen = history(case).last()
            val indices = MaiaEncoder.legalMoves(fen).map { it.first }.sorted()
            assertEquals(case.legalIndices.toList(), indices, case.name)
        }
    }

    @Test
    fun predictorDecodesTheModelsChoiceBackToTheRealBoard() = runTest {
        // Black to move: the model sees a mirrored board, so its top index must be un-mirrored.
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 4 4"
        val favourite = MaiaEncoder.moveIndex(MaiaEncoder.mirrorMove("g8f6"))
        val runtime = object : MaiaRuntime {
            var rating = 0
            override fun moveLogits(tokens: FloatArray, rating: Int): FloatArray {
                this.rating = rating
                return FloatArray(MaiaEncoder.VOCAB).also { it[favourite] = 10f }
            }
            override fun close() = Unit
        }
        val stats = MaiaHumanMovePredictor(runtime).predict(ExplorerPosition(fen), RatingBand.R1400)!!
        assertEquals(HumanMoveSource.MAIA, stats.source)
        assertEquals(1500, runtime.rating)
        assertEquals("g8f6" to "Nf6", stats.moves.first().uci to stats.moves.first().san)
        assertTrue(stats.moves.first().share > 0.99)
        assertEquals(DEFAULT_TOP_MOVES, stats.moves.size)
    }

    @Test
    fun beforePlyCarriesAtMostEightPositionsEndingWithTheCurrentOne() {
        val records = MaiaGolden.cases.first { it.name == "sicilian_history_white_to_move" }.let { case ->
            var state = FenConverter.fenToGameState(case.startFen)
            case.moves.map { uci -> state = applyUci(state, uci); MoveRecord(uci, uci, FenConverter.gameStateToFen(state)) }
        }
        val first = ExplorerPosition.beforePly(records, 0)
        assertEquals(listOf(FenConverter.STARTING_FEN), first.history)
        val late = ExplorerPosition.beforePly(records, records.lastIndex)
        assertEquals(MAIA_HISTORY, late.history.size)
        assertEquals(records[records.lastIndex - 1].fenAfter, late.fen)
    }

    @Test
    fun bandsCoverEveryRatingAndDifficulty() {
        assertEquals(RatingBand.UNDER_1000, RatingBand.forRating(650))
        assertEquals(RatingBand.R1400, RatingBand.forRating(1599))
        assertEquals(RatingBand.R1600, RatingBand.forRating(1600))
        assertEquals(RatingBand.R2500, RatingBand.forRating(3100))
        assertEquals("1400–1599", RatingBand.R1400.label)
        assertEquals("2500+", RatingBand.R2500.label)
        RatingBand.entries.forEach { assertEquals(it, RatingBand.forRating(it.maiaRating), "maiaRating inside ${it.name}") }
    }

    private fun history(case: MaiaGolden.Case): List<String> {
        var state = FenConverter.fenToGameState(case.startFen)
        val fens = mutableListOf(case.startFen)
        for (uci in case.moves) {
            state = applyUci(state, uci)
            fens += FenConverter.gameStateToFen(state)
        }
        return fens
    }
}

private fun applyUci(state: com.example.myapplication.GameUiState, uci: String): com.example.myapplication.GameUiState {
    val (from, to) = com.example.myapplication.UciMoveConverter.parseUciMove(uci)
    val mover = if (state.turn == com.example.myapplication.Set.WHITE) state.positionsWhite else state.positionsBlack
    val promotion = uci.getOrNull(4)?.let { c -> com.example.myapplication.PromotionType.entries.first { it.uciChar == c } }
    return com.example.myapplication.applyMove(state, mover.indexOf(from), to, promotion)
}
