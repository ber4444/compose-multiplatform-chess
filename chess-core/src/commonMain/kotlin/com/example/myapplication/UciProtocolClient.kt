package com.example.myapplication

import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class UciProtocolClient(private val transport: UciTransport) {
    companion object {
        const val DEFAULT_THINK_TIME_MS = 1000L
        const val EVAL_DEPTH = 12
        const val HANDSHAKE_TIMEOUT_MS = 5000L
        const val REPLY_GRACE_MS = 5000L
        private val logger = Logger.withTag("UciProtocolClient")
    }

    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private var isReady = false

    // Engine difficulty (issue #39 Phase 4). `null` skillLevel = unset; movetime defaults to the
    // classic think budget. configure() sends `setoption name Skill Level value N` + an isready sync.
    private var skillLevel: Int? = null
    private var thinkTimeMs: Long = DEFAULT_THINK_TIME_MS

    suspend fun start(timeoutMs: Long = HANDSHAKE_TIMEOUT_MS): Boolean = mutex.withLock {
        if (isReady) return true
        transport.start { line -> incoming.trySend(line) }
        transport.send("uci")
        if (!awaitLinePrefix("uciok", timeoutMs)) return false
        transport.send("isready")
        if (!awaitLinePrefix("readyok", timeoutMs)) return false
        isReady = true
        // Apply any difficulty configured before start (so it takes effect on the first move).
        skillLevel?.let { applySkillLevel(it) }
        true
    }

    /**
     * Applies [difficulty]'s Stockfish `Skill Level` (sent as `setoption`) and stores its movetime
     * budget for subsequent `go movetime` calls. No-op-friendly: safe to call before [start] (the
     * option is applied once the engine is ready) or after (sent immediately). Mirrors the handshake's
     * isready-sync so the option is committed before the next `go`.
     */
    suspend fun configure(difficulty: EngineDifficulty) = mutex.withLock {
        skillLevel = difficulty.skillLevel
        thinkTimeMs = difficulty.thinkTimeMs
        if (isReady) applySkillLevel(difficulty.skillLevel)
    }

    private suspend fun applySkillLevel(level: Int) {
        transport.send("setoption name Skill Level value $level")
        transport.send("isready")
        awaitLinePrefix("readyok", REPLY_GRACE_MS)
    }

    suspend fun bestMove(fen: String, thinkTimeMs: Long = this.thinkTimeMs): BestMoveResult? = mutex.withLock {
        if (!isReady) return null
        drainPending()
        transport.send("position fen $fen")
        transport.send("go movetime $thinkTimeMs")
        var lastEval: Int? = null
        val rawResult = withTimeoutOrNull(thinkTimeMs + REPLY_GRACE_MS) {
            var move: String? = null
            for (line in incoming) {
                UciEvaluation.parseInfoScore(line)?.let { lastEval = it }
                if (line.startsWith("bestmove")) {
                    move = line
                    break
                }
            }
            move
        } ?: return null
        val uci = parseBestMove(rawResult) ?: return null
        
        // Convert to White's perspective
        val cpPerspective = if (lastEval != null) {
            UciEvaluation.toWhitePerspective(lastEval!!, UciEvaluation.isWhiteToMove(fen))
        } else null
        
        return BestMoveResult(uci, cpPerspective)
    }

    /** Centipawns from WHITE's perspective; mirrors BaseStockfishEngine.evaluate. */
    suspend fun evaluate(fen: String, depth: Int = EVAL_DEPTH, thinkTimeMs: Long? = null, timeoutMs: Long = REPLY_GRACE_MS): Int? = mutex.withLock {
        if (!isReady) return null
        drainPending()
        transport.send("position fen $fen")
        if (thinkTimeMs != null) {
            transport.send("go movetime $thinkTimeMs")
        } else {
            transport.send("go depth $depth")
        }
        var lastEval: Int? = null
        val raw = withTimeoutOrNull(if (thinkTimeMs != null) thinkTimeMs + REPLY_GRACE_MS else timeoutMs) {
            for (line in incoming) {
                UciEvaluation.parseInfoScore(line)?.let { lastEval = it }
                if (line.startsWith("bestmove")) break   // MUST consume bestmove (queue hygiene)
            }
            lastEval
        } ?: return null
        UciEvaluation.toWhitePerspective(raw, UciEvaluation.isWhiteToMove(fen))
    }

    fun close() {
        isReady = false
        transport.close()
        incoming.close()
    }

    private suspend fun awaitLinePrefix(prefix: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            for (line in incoming) if (line.startsWith(prefix)) return@withTimeoutOrNull true
            false
        } ?: false

    private suspend fun awaitBestMoveLine(timeoutMs: Long): String? =
        withTimeoutOrNull(timeoutMs) {
            for (line in incoming) if (line.startsWith("bestmove")) return@withTimeoutOrNull line
            null
        }

    /** Discard stale lines (e.g. a bestmove that arrived after a previous call timed out). */
    private fun drainPending() {
        while (incoming.tryReceive().isSuccess) { /* drop */ }
    }

    private fun parseBestMove(line: String): String? {
        val parts = line.split(" ")
        val idx = parts.indexOf("bestmove")
        val move = if (idx != -1) parts.getOrNull(idx + 1) else null
        return if (move == null || move == "(none)") null else move
    }
}
