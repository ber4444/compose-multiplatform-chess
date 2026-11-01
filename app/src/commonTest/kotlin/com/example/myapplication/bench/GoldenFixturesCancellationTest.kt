package com.example.myapplication.bench

import com.example.myapplication.BestMoveResult
import com.example.myapplication.ChessEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GoldenFixturesCancellationTest {
    @Test
    fun cancellationStopsFixtureAssessment() = runTest {
        val engine = object : ChessEngine {
            override suspend fun getBestMove(fen: String, thinkTimeMs: Long?): BestMoveResult? = null
            override suspend fun evaluate(fen: String, thinkTimeMs: Long?): Int? =
                throw CancellationException("Benchmark owner was destroyed")
            override fun close() {}
        }
        assertFailsWith<CancellationException> {
            GoldenFixtures.load(
                """[{"id":"opening","fen":"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1","bestMoveUci":"e2e4"}]""",
                engine,
                thinkTimeMs = 1,
            )
        }
    }
}
