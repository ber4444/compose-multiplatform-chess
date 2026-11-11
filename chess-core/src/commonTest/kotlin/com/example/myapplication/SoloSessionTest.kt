package com.example.myapplication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SoloSessionTest {
    @Test
    fun pausedSoloSessionCannotEvaluateOrPlay() = runTest {
        var searches = 0
        val engine = object : ChessEngine {
            override suspend fun getBestMove(fen: String, thinkTimeMs: Long?): BestMoveResult? {
                searches++
                return null
            }

            override suspend fun evaluate(fen: String, thinkTimeMs: Long?): Int? {
                searches++
                return null
            }

            override fun close() = Unit
        }
        val model = GameViewModel()
        try {
            model.attachEngine(engine)
            model.setSoloPaused(true)
            model.computeHintDirectly()
            model.offerDraw()
            model.playerMove(4, 4 to 4)
            assertTrue(model.soloPaused.value)
            assertFalse(model.playerMoveCheck())
            assertEquals(0, searches)
            assertEquals(emptyList(), model.gameState.value.moveHistory)
            model.setSoloPaused(false)
            assertTrue(model.playerMoveCheck())
        } finally {
            model.close()
        }
    }

    @Test
    fun restoreAppliesSideAndPersistsWithoutEmittingAnUpload() {
        var localSave: GameSnapshot? = null
        var uploads = 0
        val sink = object : GameSnapshotSink {
            override fun save(snapshot: GameSnapshot) { localSave = snapshot }
            override fun clear() { localSave = null }
        }
        val model = GameViewModel(snapshotSink = sink)
        try {
            val snapshot = GameSnapshot("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
            model.setSnapshotObserver { _, _ -> uploads++ }
            model.restoreSoloSnapshot(snapshot, Set.BLACK)
            assertEquals(Set.BLACK, model.playerSide)
            assertEquals(Set.BLACK, model.gameState.value.turn)
            assertEquals(snapshot, localSave)
            assertEquals(0, uploads)
            model.resetGame()
            assertEquals(1, uploads)
        } finally {
            model.close()
        }
    }
}
