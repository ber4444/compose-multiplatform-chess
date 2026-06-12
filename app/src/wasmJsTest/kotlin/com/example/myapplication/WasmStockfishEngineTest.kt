package com.example.myapplication

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class WasmStockfishEngineTest {
    @Test
    fun engineStartsAndEvaluates() = runTest {
        val engine = WasmStockfishEngine()
        
        // Wait for handshake
        val started = engine.start()
        assertTrue(started, "Engine should start successfully")
        
        // Test evaluation (initial position)
        val eval = engine.evaluate("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        assertNotNull(eval, "Evaluation should not be null")
        
        engine.close()
    }
}
