package com.example.myapplication

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UciProtocolClientTest {

    private class FakeUciTransport : UciTransport {
        var onLine: ((String) -> Unit)? = null
        val commands = mutableListOf<String>()
        var closed = false

        override fun start(onLine: (String) -> Unit) {
            this.onLine = onLine
        }
        override fun send(command: String) {
            commands.add(command)
        }
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `handshake success`() = runTest {
        val transport = FakeUciTransport()
        val client = UciProtocolClient(transport)
        
        val startJob = async { client.start() }
        
        runCurrent()
        assertEquals("uci", transport.commands.last())
        
        transport.onLine?.invoke("uciok")
        runCurrent()
        assertEquals("isready", transport.commands.last())
        
        transport.onLine?.invoke("readyok")
        assertTrue(startJob.await())
    }

    @Test
    fun `handshake timeout`() = runTest {
        val transport = FakeUciTransport()
        val client = UciProtocolClient(transport)
        
        val startJob = async { client.start(timeoutMs = 100) }
        testScheduler.advanceTimeBy(101)
        assertFalse(startJob.await())
    }

    @Test
    fun `bestMove success`() = runTest {
        val transport = FakeUciTransport()
        val client = UciProtocolClient(transport)
        
        val startJob = async { client.start() }
        runCurrent()
        transport.onLine?.invoke("uciok")
        runCurrent()
        transport.onLine?.invoke("readyok")
        startJob.await()
        
        transport.commands.clear()
        
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val moveJob = async { client.bestMove(fen, 100) }
        runCurrent()
        
        assertEquals(2, transport.commands.size)
        assertEquals("position fen $fen", transport.commands[0])
        assertEquals("go movetime 100", transport.commands[1])
        
        transport.onLine?.invoke("info depth 10")
        transport.onLine?.invoke("bestmove e2e4 ponder e7e5")
        
        assertEquals("e2e4", moveJob.await()?.uci)
    }

    @Test
    fun `evaluate parsing`() = runTest {
        val transport = FakeUciTransport()
        val client = UciProtocolClient(transport)

        val startJob = async { client.start() }
        runCurrent()
        transport.onLine?.invoke("uciok")
        runCurrent()
        transport.onLine?.invoke("readyok")
        startJob.await()

        // White to move, eval 50 -> returns 50
        val evalJob = async { client.evaluate("... w ...") }
        runCurrent()

        transport.onLine?.invoke("info score cp 50")
        transport.onLine?.invoke("bestmove e2e4") // must trigger completion

        assertEquals(50, evalJob.await())
    }

    // --- Engine difficulty (issue #39 Phase 4) ---

    @Test
    fun `configure sends setoption Skill Level and isready sync`() = runTest {
        val transport = FakeUciTransport()
        val client = UciProtocolClient(transport)

        val startJob = async { client.start() }
        runCurrent()
        transport.onLine?.invoke("uciok")
        runCurrent()
        transport.onLine?.invoke("readyok")
        startJob.await()

        transport.commands.clear()
        val configureJob = async { client.configure(EngineDifficulty.EASY) }
        runCurrent()

        // Skill Level 2 for EASY, then the isready sync.
        assertTrue(transport.commands.contains("setoption name Skill Level value 2"))
        assertEquals("isready", transport.commands.last())
        transport.onLine?.invoke("readyok")
        runCurrent()
        configureJob.await()
    }

    @Test
    fun `bestMove uses the configured difficulty movetime`() = runTest {
        val transport = FakeUciTransport()
        val client = UciProtocolClient(transport)

        val startJob = async { client.start() }
        runCurrent()
        transport.onLine?.invoke("uciok")
        runCurrent()
        transport.onLine?.invoke("readyok")
        startJob.await()

        // EASY = 200ms movetime. Configure, then bestMove should use it (not the default 1000ms).
        val configureJob = async { client.configure(EngineDifficulty.EASY) }
        runCurrent()
        transport.onLine?.invoke("readyok")  // satisfy the isready sync from configure
        runCurrent()
        configureJob.await()

        transport.commands.clear()
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val moveJob = async { client.bestMove(fen) }
        runCurrent()

        assertEquals("go movetime 200", transport.commands.last { it.startsWith("go movetime") })

        transport.onLine?.invoke("bestmove e2e4")
        assertEquals("e2e4", moveJob.await()?.uci)
    }
}
