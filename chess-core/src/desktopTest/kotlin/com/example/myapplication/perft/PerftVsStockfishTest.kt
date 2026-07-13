package com.example.myapplication.perft

import com.example.myapplication.FenConverter
import com.example.myapplication.GameUiState
import com.example.myapplication.PromotionType
import com.example.myapplication.Set
import com.example.myapplication.UciMoveConverter
import com.example.myapplication.applyMove
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * Oracle 2 — Stockfish `go perft` divide differ. This is the localizer that makes the
 * autonomous loop autonomous: when perft total disagrees with Stockfish's, the divide diff
 * pinpoints the exact root move (and, one ply deeper, the next diverging move) so an agent
 * gets a concrete FEN + move to investigate without an LLM-as-judge or human in the loop.
 *
 * Skips automatically when no `stockfish` binary is present (see [StockfishPerft.isAvailable]);
 * the canonical gate ([PerftTest] / [PerftCanonicalGateTest]) is the portable backbone.
 *
 * On failure, writes a localized trail to `build/perft-divergence.txt` (see [localizeDivergence]).
 */
class PerftVsStockfishTest {

    @Test
    fun canonical_positions_match_stockfish() {
        assumeStockfishAvailable()
        StockfishPerft().use { sf ->
            for (position in PerftPositions.ALL) {
                val state = FenConverter.fenToGameState(position.fen)
                val depth = canonicalDivideDepth(position)
                val report = localizeDivergence(state, depth, sf)
                assertNull(
                    report,
                    "Position ${position.name} diverged at depth $depth.\n${report?.render(state, depth)}",
                )
            }
        }
    }

    @Test
    fun random_walk_positions_match_stockfish() {
        assumeStockfishAvailable()
        val seed = RANDOM_WALK_SEED
        val rng = Random(seed)
        StockfishPerft().use { sf ->
            var state = FenConverter.fenToGameState(FenConverter.STARTING_FEN)
            // Log the seed so failures are reproducible.
            println("[PerftVsStockfishTest] random walk seed=$seed")
            var accepted = 0
            for (step in 0 until RANDOM_WALK_STEPS) {
                val moves = legalMovesFor(state)
                if (moves.isEmpty()) {
                    println("[PerftVsStockfishTest] walk ended at step $step: no legal moves (terminal position)")
                    break
                }
                val move = moves.random(rng)
                val newState = applyMove(state, move.pieceIndex, move.to, move.promotion)

                // Legality guard: the side that just moved must not have left its own king
                // capturable. The legal-move generator filters for this, but if the bug under
                // test is exactly such a gap, the walk could reach an illegal position; handing
                // that to Stockfish yields numbers that look like divergences but aren't. Skip
                // + log instead of polluting the diff.
                val prevMover = state.turn
                val prevMoverInCheck = when (prevMover) {
                    Set.WHITE -> newState.inCheckWhite
                    Set.BLACK -> newState.inCheckBlack
                }
                if (prevMoverInCheck) {
                    println(
                        "[PerftVsStockfishTest] step $step: skip illegal-looking position after $prevMover move " +
                            "(generator left own king in check); FEN=${FenConverter.gameStateToFen(newState)}"
                    )
                    continue
                }

                state = newState
                accepted++

                // Diff every K accepted steps at a shallow depth that keeps Stockfish fast.
                if (accepted % DIFF_EVERY_N_ACCEPTED == 0) {
                    val report = localizeDivergence(state, DIFF_DEPTH, sf)
                    assertNull(
                        report,
                        "Walk diverged at step=$step (accepted=$accepted, seed=$seed) at depth $DIFF_DEPTH.\n" +
                            report?.render(state, DIFF_DEPTH),
                    )
                }
            }
            assertTrue(accepted > 0, "Random walk accepted zero moves (seed=$seed) — generator is broken")
            println("[PerftVsStockfishTest] random walk accepted $accepted positions; all matched Stockfish at depth $DIFF_DEPTH")
        }
    }

    private fun assumeStockfishAvailable() {
        Assume.assumeTrue("Stockfish binary not available — skipping Oracle 2", StockfishPerft.isAvailable())
    }

    /**
     * Compare [perftDivide] against Stockfish's `go perft` for [state]. If they agree, return
     * null. Otherwise, descend one ply into the first diverging root move and diff again,
     * repeating until the divide matches or [depth] bottoms out at 1. Each step of the descent
     * is recorded so the resulting trail points an agent at the exact FEN + move where the
     * generator parts ways from a correct one.
     */
    private fun localizeDivergence(
        state: GameUiState,
        depth: Int,
        sf: StockfishPerft,
    ): DivergenceReport? {
        val trail = mutableListOf<DivergenceLevel>()
        var current = state
        var currentDepth = depth

        while (currentDepth >= 1) {
            val fen = FenConverter.gameStateToFen(current)
            val appMoves = perftDivide(current, currentDepth)
            val sfResult = sf.divide(fen, currentDepth)
            val sfMoves = sfResult.divide
            val diverging = (appMoves.keys + sfMoves.keys)
                .filter { appMoves[it] != sfMoves[it] }
                .sorted()

            val level = DivergenceLevel(
                fen = fen,
                depth = currentDepth,
                appMoves = appMoves,
                sfMoves = sfMoves,
                sfTotal = sfResult.totalSearched,
                divergingMoves = diverging,
            )
            trail.add(level)

            if (diverging.isEmpty()) return null  // agreement at this ply
            if (currentDepth <= 1) break           // can't descend further

            // Recurse one ply into the first diverging root move.
            val firstUci = diverging.first()
            val parsed = parseUci(firstUci) ?: break
            val (from, to, promo) = parsed
            val allyPositions = if (current.turn == Set.WHITE) current.positionsWhite else current.positionsBlack
            val pieceIndex = allyPositions.indexOf(from)
            if (pieceIndex == -1) break  // generator didn't produce this move — flag and stop
            current = applyMove(current, pieceIndex, to, promo)
            currentDepth -= 1
        }

        return DivergenceReport(trail)
    }

    private fun parseUci(uci: String): Triple<Pair<Int, Int>, Pair<Int, Int>, PromotionType?>? {
        if (uci.length !in 4..5) return null
        val from = runCatching { UciMoveConverter.uciSquareToPosition(uci.substring(0, 2)) }.getOrNull() ?: return null
        val to = runCatching { UciMoveConverter.uciSquareToPosition(uci.substring(2, 4)) }.getOrNull() ?: return null
        val promo = if (uci.length == 5) PromotionType.fromUciChar(uci[4]) else null
        return Triple(from, to, promo)
    }

    private fun canonicalDivideDepth(position: PerftPosition): Int = when (position) {
        PerftPositions.START -> 3
        PerftPositions.KIWIPETE -> 3
        PerftPositions.POSITION_3 -> 4
        PerftPositions.POSITION_4 -> 3
        PerftPositions.POSITION_5 -> 3
        PerftPositions.POSITION_6 -> 3
        else -> 3
    }

    companion object {
        // Visible for any future deep-walk variant; fixed so failures reproduce.
        const val RANDOM_WALK_SEED: Long = 0xC0FFEEL
        const val RANDOM_WALK_STEPS: Int = 60
        const val DIFF_EVERY_N_ACCEPTED: Int = 5
        const val DIFF_DEPTH: Int = 3
    }
}

/** One ply of the divide-diff descent. */
private data class DivergenceLevel(
    val fen: String,
    val depth: Int,
    val appMoves: Map<String, Long>,
    val sfMoves: Map<String, Long>,
    val sfTotal: Long?,
    val divergingMoves: List<String>,
)

/** A full trail of [DivergenceLevel]s from the original FEN down to the deepest mismatch. */
private class DivergenceReport(val trail: List<DivergenceLevel>) {

    /** Multi-line, agent-friendly report. Persisted to `build/perft-divergence.txt` on failure. */
    fun render(rootState: GameUiState, rootDepth: Int): String {
        val sb = StringBuilder()
        sb.appendLine("Perft divergence detected at depth $rootDepth:")
        sb.appendLine("  Root FEN: ${FenConverter.gameStateToFen(rootState)}")
        sb.appendLine("  Trail (${trail.size} level(s)):")
        for ((i, level) in trail.withIndex()) {
            val appTotal = level.appMoves.values.sum()
            val sfTotal = level.sfTotal ?: level.sfMoves.values.sum()
            sb.appendLine("  [ply $i, depth=${level.depth}] FEN: ${level.fen}")
            sb.appendLine("      app total=$appTotal, stockfish total=$sfTotal, differing moves=${level.divergingMoves.size}")
            for (move in level.divergingMoves.take(MAX_MOVES_PER_LEVEL)) {
                val app = level.appMoves[move]
                val sf = level.sfMoves[move]
                sb.appendLine("      $move: app=${app ?: "absent"} sf=${sf ?: "absent"}")
            }
            if (level.divergingMoves.size > MAX_MOVES_PER_LEVEL) {
                sb.appendLine("      ... and ${level.divergingMoves.size - MAX_MOVES_PER_LEVEL} more")
            }
        }
        sb.appendLine()
        sb.appendLine("Next step: read the deepest [ply N] above — its FEN is the position where the app's")
        sb.appendLine("generator produces a different subtree count than Stockfish for the listed move(s).")
        sb.appendLine("Inspect the corresponding rule in app/src/commonMain/.../Move.kt or applyMove.")

        // Persist so the autonomous loop can pick it up via the filesystem.
        runCatching {
            val out = projectRoot().resolve("build").apply { toFile().mkdirs() }
            out.resolve("perft-divergence.txt").toFile().writeText(sb.toString())
        }
        return sb.toString()
    }

    private companion object {
        const val MAX_MOVES_PER_LEVEL = 12

        /**
         * Walk up from the test JVM's CWD until we find `settings.gradle.kts`. The desktop test
         * task runs with CWD = `app/`, so `build/perft-divergence.txt` written naively lands in
         * `app/build/`. Anchoring on the project root keeps the brief's `build/...` path honest.
         */
        fun projectRoot(): java.nio.file.Path {
            var dir = java.nio.file.Paths.get(System.getProperty("user.dir"))
            while (dir.parent != null && !java.nio.file.Files.exists(dir.resolve("settings.gradle.kts"))) {
                dir = dir.parent
            }
            return dir
        }
    }
}
