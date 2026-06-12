package com.example.myapplication

import co.touchlab.kermit.Logger
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Base class containing all UCI protocol communication logic shared between
 * the Android ([StockfishEngine]) and Desktop ([DesktopStockfishEngine]) engines.
 *
 * Subclasses implement [resolveExecutablePath] to supply the command/path used
 * to launch the Stockfish process. Returning `null` causes [start] to skip
 * process creation and immediately enable the embedded CPU fallback.
 */
abstract class BaseStockfishEngine : ChessEngine {

    companion object {
        const val DEFAULT_THINK_TIME_MS = 1000L
        const val EVAL_DEPTH = 12
        const val EVAL_TIMEOUT_MS = 5000L
        private val logger = Logger.withTag("StockfishEngine")
    }

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var readerThread: Thread? = null
    private val lineQueue = LinkedBlockingQueue<String>()

    @Volatile
    protected var isReady = false

    /** Return the command or absolute path to launch the engine, or null to use embedded fallback. */
    protected abstract fun resolveExecutablePath(): String?

    fun start(): Boolean {
        if (process != null) {
            logger.i { "Stockfish engine already running" }
            return isReady
        }

        val executablePath = resolveExecutablePath()
        if (executablePath == null) {
            isReady = true
            logger.i { "No Stockfish executable found; using embedded fallback engine" }
            return true
        }

        return try {
            process = ProcessBuilder(executablePath)
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(process!!.inputStream))
            writer = OutputStreamWriter(process!!.outputStream)

            readerThread = Thread({
                try {
                    var line = reader.readLine()
                    while (line != null) {
                        lineQueue.put(line)
                        line = reader.readLine()
                    }
                } catch (_: IOException) {
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }, "StockfishReader").apply {
                isDaemon = true
                start()
            }

            sendCommand("uci")
            if (!waitForLine("uciok", timeoutMs = 5000)) {
                logger.e { "Engine did not respond with 'uciok'" }
                shutdown()
                return false
            }

            sendCommand("isready")
            if (!waitForLine("readyok", timeoutMs = 5000)) {
                logger.e { "Engine did not respond with 'readyok'" }
                shutdown()
                return false
            }

            isReady = true
            logger.i { "Stockfish engine started successfully" }
            true
        } catch (e: IOException) {
            logger.e(e) { "Failed to start Stockfish engine" }
            shutdown()
            false
        }
    }

    override suspend fun getBestMove(fen: String): String? = withContext(Dispatchers.IO) { getBestMove(fen, DEFAULT_THINK_TIME_MS) }

    fun getBestMove(fen: String, thinkTimeMs: Long): String? {
        if (!isReady || process == null) return getEmbeddedBestMove(fen)

        return try {
            sendCommand("position fen $fen")
            sendCommand("go movetime $thinkTimeMs")
            val bestMoveLine = waitForBestMove(timeoutMs = thinkTimeMs + 5000)
            if (bestMoveLine != null) {
                val parts = bestMoveLine.split(" ")
                val idx = parts.indexOf("bestmove")
                if (idx != -1 && idx + 1 < parts.size) {
                    parts[idx + 1]
                } else {
                    logger.w { "Could not parse bestmove from: $bestMoveLine" }
                    null
                }
            } else {
                logger.w { "Timed out waiting for bestmove" }
                null
            }
        } catch (e: IOException) {
            logger.e(e) { "Error communicating with engine" }
            null
        }
    }

    protected fun getEmbeddedBestMove(fen: String): String? {
        return try {
            val gameState = FenConverter.fenToGameState(fen)
            val isWhiteTurn = gameState.turn == Set.WHITE
            val allyPositions  = if (isWhiteTurn) gameState.positionsWhite else gameState.positionsBlack
            val allyPieces     = if (isWhiteTurn) gameState.piecesWhite   else gameState.piecesBlack
            val enemyPositions = if (isWhiteTurn) gameState.positionsBlack else gameState.positionsWhite
            val enemyPieces    = if (isWhiteTurn) gameState.piecesBlack   else gameState.piecesWhite

            val move = pickMoveCPU(enemyPositions, enemyPieces, allyPositions, allyPieces)
            if (move.pieceIndex == -1 || move.position == INVALID_POSITION) {
                logger.w { "Embedded fallback engine could not find a legal move" }
                null
            } else {
                UciMoveConverter.appMoveToUci(allyPositions[move.pieceIndex], move.position)
            }
        } catch (e: IllegalArgumentException) {
            logger.e(e) { "Invalid FEN supplied to embedded fallback engine" }
            null
        }
    }

    override suspend fun evaluate(fen: String): Int? = withContext(Dispatchers.IO) {
        if (!isReady || process == null) return@withContext null

        return@withContext try {
            sendCommand("position fen $fen")
            sendCommand("go depth $EVAL_DEPTH")
            val rawEval = waitForEvaluation(EVAL_TIMEOUT_MS) ?: return@withContext null
            UciEvaluation.toWhitePerspective(rawEval, UciEvaluation.isWhiteToMove(fen))
        } catch (e: IOException) {
            logger.e(e) { "Error evaluating position" }
            null
        }
    }

    override fun close() = shutdown()

    fun shutdown() {
        isReady = false
        try { if (writer != null) sendCommand("quit") } catch (_: IOException) {}
        try { writer?.close() } catch (_: IOException) {}
        readerThread?.interrupt()
        process?.destroy()
        process = null
        writer = null
        readerThread = null
        lineQueue.clear()
        logger.i { "Stockfish engine shut down" }
    }

    protected fun sendCommand(command: String) {
        writer?.let { it.write("$command\n"); it.flush() }
    }

    private fun waitForLine(expected: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return false
            val line = lineQueue.poll(remaining, TimeUnit.MILLISECONDS) ?: return false
            if (line.startsWith(expected)) return true
        }
    }

    private fun waitForBestMove(timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val line = lineQueue.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
            if (line.startsWith("bestmove")) return line
        }
    }

    private fun waitForEvaluation(timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastEval: Int? = null
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val line = lineQueue.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
            
            val parsedEval = UciEvaluation.parseInfoScore(line)
            if (parsedEval != null) {
                lastEval = parsedEval
            }
            
            if (line.startsWith("bestmove")) {
                return lastEval
            }
        }
    }
}
