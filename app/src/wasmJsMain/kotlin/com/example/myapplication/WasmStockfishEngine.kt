package com.example.myapplication

/** Keep in sync with the vendored filename under app/src/wasmJsMain/resources/stockfish/ (Step 1). */
private const val STOCKFISH_WORKER_URL = "stockfish/stockfish-18-lite-single.js"

class WasmStockfishEngine(
    transport: UciTransport = WorkerUciTransport(STOCKFISH_WORKER_URL)
) : ChessEngine {
    private val client = UciProtocolClient(transport)

    /** False on handshake timeout (e.g. worker file 404) — caller then skips attachEngine. */
    suspend fun start(): Boolean = client.start()

    override suspend fun getBestMove(fen: String): String? = client.bestMove(fen)

    override suspend fun evaluate(fen: String): Int? = client.evaluate(fen)

    override fun close() = client.close()
}
