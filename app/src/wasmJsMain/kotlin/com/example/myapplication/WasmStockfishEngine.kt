package com.example.myapplication

/** Keep in sync with the vendored filename under app/src/wasmJsMain/resources/stockfish/ (Step 1). */
private const val STOCKFISH_WORKER_URL = "stockfish/stockfish-18-lite-single.js"

class WasmStockfishEngine(
    transport: UciTransport = WorkerUciTransport(STOCKFISH_WORKER_URL)
) : ChessEngine {
    private val client = UciProtocolClient(transport)

    /** False on handshake timeout (e.g. worker file 404) — caller then skips attachEngine. */
    suspend fun start(): Boolean = client.start()

    override suspend fun getBestMove(fen: String, thinkTimeMs: Long?): BestMoveResult? =
        // UciProtocolClient already defaults this to its configured budget.
        if (thinkTimeMs == null) client.bestMove(fen) else client.bestMove(fen, thinkTimeMs)

    override suspend fun evaluate(fen: String, thinkTimeMs: Long?): Int? = client.evaluate(fen, thinkTimeMs = thinkTimeMs)

    override suspend fun configure(difficulty: EngineDifficulty) = client.configure(difficulty)

    override fun close() = client.close()
}
