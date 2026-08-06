package com.example.myapplication

import co.touchlab.kermit.Logger
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Serializes every command/response exchange with the engine process. **Load-bearing — do not
     * remove or narrow to a write-only lock.**
     *
     * UCI is a stateful conversation over one pipe: a caller sends `position fen …` then `go …`,
     * then drains [lineQueue] until it sees the reply it wants. Two concurrent callers on one
     * process corrupt each other three ways:
     *
     *  - **Position clobbering.** Both `position fen` commands race; the engine ends up on whichever
     *    landed last, and the loser's `go` analyses a different board than it asked about. It returns
     *    a plausible number for the wrong position — no exception, nothing in the log.
     *  - **Line theft.** [lineQueue] is shared, and the two drain loops accept different lines
     *    ([waitForBestMove] wants `bestmove`, [waitForEvaluation] wants `info … score`). Interleaved,
     *    each consumes lines the other needed — an `evaluate` can swallow the `bestmove` a
     *    [getBestMove] is blocked on, which then times out and silently degrades to the embedded
     *    CPU fallback.
     *  - **Protocol violation.** Issuing `go` while a search is running is undefined in UCI; the
     *    engine expects `stop` first.
     *
     * The other two transports already serialize: `UciProtocolClient` (wasm) holds a `Mutex` across
     * `bestMove`/`evaluate`/`configure`, and the iOS bridge funnels every command through
     * `SharedStockfishCore.runSearch` under an `NSLock`. This JVM path — Android *and* desktop — was
     * the only one without the guard.
     *
     * Not held by [start] or [shutdown]: both are lifecycle-only, called before the engine is
     * attached and after it is detached respectively, so no search can be in flight. [shutdown]
     * racing an in-flight search is a pre-existing, benign case — the search times out and falls
     * back — and fixing it properly needs `ChessEngine.close()` to become suspending.
     */
    private val uciMutex = Mutex()

    // Engine difficulty (issue #39 Phase 4). `null` skillLevel = unset; movetime defaults to the
    // classic think budget. configure() sends `setoption name Skill Level value N` once the engine
    // process is up; the embedded fallback (no process) ignores skill but still honors movetime.
    private var skillLevel: Int? = null
    private var thinkTimeMs: Long = DEFAULT_THINK_TIME_MS

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

            // Apply any difficulty configured before start once the engine is up but before isReady
            // flips, so the first move uses the configured skill + movetime.
            skillLevel?.let { sendSkillLevel(it) }

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

    /**
     * Applies [difficulty]'s Stockfish `Skill Level` (sent as `setoption`) and stores its movetime
     * budget for subsequent `go movetime` calls. Safe to call before [start] (the option is applied
     * once the engine process is up) or after (sent immediately). The embedded fallback (no process)
     * ignores the skill level but still honors the movetime.
     */
    override suspend fun configure(difficulty: EngineDifficulty) = uciMutex.withLock {
        withContext(Dispatchers.IO) {
            skillLevel = difficulty.skillLevel
            thinkTimeMs = difficulty.thinkTimeMs
            if (process != null && isReady) sendSkillLevel(difficulty.skillLevel)
        }
    }

    private fun sendSkillLevel(level: Int) {
        sendCommand("setoption name Skill Level value $level")
    }

    override suspend fun getBestMove(fen: String, thinkTimeMs: Long?): BestMoveResult? = uciMutex.withLock {
        withContext(Dispatchers.IO) { searchBestMove(fen, thinkTimeMs ?: this@BaseStockfishEngine.thinkTimeMs) }
    }

    /**
     * Private on purpose: it writes commands and drains [lineQueue] without holding [uciMutex], so
     * it is only safe from a caller that already holds it. Was public with no callers outside this
     * file — exposing it would reintroduce the unguarded path the mutex exists to close.
     */
    private fun searchBestMove(fen: String, thinkTimeMs: Long): BestMoveResult? {
        if (!isReady || process == null) return getEmbeddedBestMove(fen)

        return try {
            sendCommand("position fen $fen")
            sendCommand("go movetime $thinkTimeMs")
            val bestMoveResult = waitForBestMove(timeoutMs = thinkTimeMs + 5000, fen = fen)
            if (bestMoveResult != null) {
                bestMoveResult
            } else {
                logger.w { "Timed out waiting for bestmove or failed to parse" }
                null
            }
        } catch (e: IOException) {
            logger.e(e) { "Error communicating with engine" }
            null
        }
    }

    protected fun getEmbeddedBestMove(fen: String): BestMoveResult? {
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
                val uci = UciMoveConverter.appMoveToUci(allyPositions[move.pieceIndex], move.position)
                BestMoveResult(uci, null)
            }
        } catch (e: IllegalArgumentException) {
            logger.e(e) { "Invalid FEN supplied to embedded fallback engine" }
            null
        }
    }

    override suspend fun evaluate(fen: String, thinkTimeMs: Long?): Int? = uciMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isReady || process == null) return@withContext null

            return@withContext try {
                sendCommand("position fen $fen")
                if (thinkTimeMs != null) {
                    sendCommand("go movetime $thinkTimeMs")
                } else {
                    sendCommand("go depth $EVAL_DEPTH")
                }
                val rawEval = waitForEvaluation(if (thinkTimeMs != null) thinkTimeMs + 5000L else EVAL_TIMEOUT_MS) ?: return@withContext null
                UciEvaluation.toWhitePerspective(rawEval, UciEvaluation.isWhiteToMove(fen))
            } catch (e: IOException) {
                logger.e(e) { "Error evaluating position" }
                null
            }
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

    private fun waitForBestMove(timeoutMs: Long, fen: String): BestMoveResult? {
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
                val parts = line.split(" ")
                val idx = parts.indexOf("bestmove")
                val uci = if (idx != -1 && idx + 1 < parts.size) parts[idx + 1] else return null
                val evalToWhite = lastEval?.let { UciEvaluation.toWhitePerspective(it, UciEvaluation.isWhiteToMove(fen)) }
                return BestMoveResult(uci, evalToWhite)
            }
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
