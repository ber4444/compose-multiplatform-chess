package com.example.myapplication

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

/**
 * Proves [BaseStockfishEngine] serializes UCI exchanges.
 *
 * UCI is a stateful conversation over one pipe. Before `uciMutex`, `getBestMove` and `evaluate`
 * wrote to the same stdin and drained the same line queue with different acceptance criteria, so
 * concurrent callers clobbered each other's `position fen` and stole each other's reply lines.
 *
 * Rather than depend on a real Stockfish install, these tests drive a **fake UCI engine** — a shell
 * script that speaks just enough of the protocol and answers `go movetime` and `go depth` with
 * *distinguishable* values. That is what makes the assertion deterministic: an interleaved run
 * returns the other command's answer, which is a hard failure rather than a flake.
 *
 * The fake sleeps briefly before replying so that unguarded concurrent calls reliably overlap. With
 * the mutex in place they queue instead, and every caller sees its own reply.
 */
class StockfishUciConcurrencyTest {

    private val scripts = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        scripts.forEach { it.delete() }
    }

    /**
     * A minimal UCI responder. `go movetime` always yields `bestmove e2e4`; `go depth` always yields
     * `info … score cp 4242`. Neither value can be produced by the other command, so a swapped or
     * stolen reply is detectable.
     */
    private fun fakeEngineScript(): File {
        val script = File.createTempFile("fake-uci", ".sh").apply {
            writeText(
                """
                #!/bin/sh
                while IFS= read -r line; do
                  case "${'$'}line" in
                    uci)      echo "id name FakeUci"; echo "uciok" ;;
                    isready)  echo "readyok" ;;
                    "go movetime"*) sleep 0.15; echo "info depth 1 score cp 11"; echo "bestmove e2e4" ;;
                    "go depth"*)    sleep 0.15; echo "info depth 12 score cp 4242"; echo "bestmove d2d4" ;;
                    quit)     exit 0 ;;
                  esac
                done
                """.trimIndent(),
            )
            setExecutable(true)
        }
        scripts += script
        return script
    }

    private fun engine(script: File) = object : BaseStockfishEngine() {
        override fun resolveExecutablePath(): String = script.absolutePath
    }

    @Test
    fun `concurrent getBestMove and evaluate do not steal each other's replies`() = runTest {
        val script = fakeEngineScript()
        if (!script.canExecute()) return@runTest // exotic filesystem; nothing to prove here
        val engine = engine(script)
        assertTrue(engine.start(), "fake engine handshake failed")

        try {
            // 4 pairs in flight at once. Unguarded, the `go depth` reply (cp 4242) gets consumed by a
            // waitForBestMove loop and the `bestmove e2e4` by a waitForEvaluation loop, so at least
            // one side comes back null or holding the other's value.
            val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
            val moves = List(4) { async { engine.getBestMove(fen) } }
            val evals = List(4) { async { engine.evaluate(fen) } }

            moves.awaitAll().forEachIndexed { i, move ->
                assertEquals("e2e4", move, "search $i got a reply that wasn't its own")
            }
            evals.awaitAll().forEachIndexed { i, eval ->
                assertEquals(4242, eval, "evaluation $i got a reply that wasn't its own")
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `configure interleaved with searches keeps every search correct`() = runTest {
        val script = fakeEngineScript()
        if (!script.canExecute()) return@runTest
        val engine = engine(script)
        assertTrue(engine.start(), "fake engine handshake failed")

        try {
            // configure() also writes to the shared pipe (`setoption name Skill Level …`), so it has
            // to hold the same lock — a setoption landing mid-search is exactly the clobbering case.
            val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
            val work = buildList {
                add(async { engine.getBestMove(fen) })
                add(async { engine.configure(EngineDifficulty.EASY); "configured" })
                add(async { engine.getBestMove(fen) })
                add(async { engine.configure(EngineDifficulty.MAX); "configured" })
            }
            val results = work.awaitAll()

            assertEquals("e2e4", results[0])
            assertEquals("e2e4", results[2])
        } finally {
            engine.close()
        }
    }
}
