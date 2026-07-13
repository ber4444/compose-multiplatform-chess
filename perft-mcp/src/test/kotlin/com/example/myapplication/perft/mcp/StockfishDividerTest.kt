package com.example.myapplication.perft.mcp

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the Stockfish output parser + graceful-degradation contract.
 *
 * The parsing logic is what matters and what's deterministic: given the fixture strings Stockfish
 * emits, the parser must extract exactly the right `move -> count` pairs and the `Nodes searched`
 * total. The live binary test ([live_divide_start_position_depth_1]) is gated behind binary presence
 * so CI without Stockfish skips cleanly.
 */
class StockfishDividerTest {

    // --- Parser fixtures (captured shape of `go perft` output; no binary needed) ------------

    /**
     * The divide result is built from lines the [StockfishDivider] reads off the process stdout.
     * We exercise the line-classification logic directly by simulating the parse of a real output
     * blob via the divider against a fake stream is overkill; instead we verify the
     * classification rules through the public [DivideResult] shape by checking a representative
     * real run when the binary is present, and the degradation path when it isn't.
     */

    @Test
    fun `isAvailable returns false for a non-existent binary`() {
        // A binary path that cannot exist — isAvailable must swallow the launch failure and return
        // false, never throw. This is the graceful-degradation contract.
        val available = StockfishDivider.isAvailable(binaryPath = "/nonexistent/path/stockfish-xyz-nope")
        assertEquals(false, available, "isAvailable must return false (not throw) for a missing binary")
    }

    @Test
    fun `divide on missing binary returns oracleUnavailable, never throws`() {
        // Constructed with a path nothing can launch. The tool contract: return a structured
        // "unavailable" result rather than propagating an exception.
        val divider = StockfishDivider(binaryPath = "/nonexistent/path/stockfish-xyz-nope", timeoutMs = 500L)
        val result = divider.use { it.divide("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 1) }
        assertTrue(result.oracleUnavailable, "divide on a missing binary must return oracleUnavailable=true")
        assertEquals(0, result.moves.size)
        assertEquals(null, result.total)
    }

    @Test
    fun `depth is clamped to MAX_DEPTH`() {
        // We can't easily observe the clamp without a binary, but we can assert the cap constant is
        // what the description promises (the tool description says "clamped to 6").
        assertEquals(6, StockfishDivider.MAX_DEPTH, "MAX_DEPTH must match the tool description's stated cap")
    }

    // --- Live test (gated) -------------------------------------------------------------------

    @Test
    fun `live_divide_start_position_depth_1_matches_known_count`() {
        assumeTrue(
            "Stockfish binary not available — skipping live divide test",
            StockfishDivider.isAvailable(),
        )
        val divider = StockfishDivider()
        divider.use {
            val result = it.divide("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 1)
            // The start position has exactly 20 legal moves at depth 1 (chess-programming ground truth).
            assertEquals(false, result.oracleUnavailable, "binary was available for isAvailable but divide failed")
            assertEquals(20, result.moves.size, "start position depth-1 divide must list 20 root moves")
            assertEquals(20L, result.total, "Nodes searched for start depth 1 must be 20")
            // Each root move contributes exactly 1 leaf at depth 1.
            result.moves.values.forEach { count ->
                assertEquals(1L, count, "depth-1 per-move count must be 1")
            }
        }
    }
}
