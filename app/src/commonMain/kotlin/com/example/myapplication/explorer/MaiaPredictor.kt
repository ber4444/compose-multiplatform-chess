package com.example.myapplication.explorer

import com.example.myapplication.FenConverter
import com.example.myapplication.SanConverter
import kotlin.math.exp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Predicts the moves humans at a rating play, from a model rather than from game data. The seam is
 * deliberately *not* `OnDeviceTextGenerator`: the output is a distribution over moves, and code —
 * not a model — decides what to say about it.
 */
interface HumanMovePredictor : AutoCloseable {
    /** `null` when the position has no legal moves (the game is over). */
    suspend fun predict(position: ExplorerPosition, band: RatingBand): HumanMoveStats?
}

/**
 * One platform's way of running the Maia-3 network: [MaiaEncoder.tokens] in, the 4352 move logits
 * out. Blocking; [MaiaHumanMovePredictor] calls it off the main thread.
 */
interface MaiaRuntime : AutoCloseable {
    /** Suspending because the web runtime (LiteRT.js) is asynchronous; the others just compute. */
    suspend fun moveLogits(tokens: FloatArray, rating: Int): FloatArray
}

/**
 * How this platform opens Maia-3, or `null` where it has no runtime. Calling the factory loads the
 * model, which is tens of megabytes and hundreds of milliseconds, so [MaiaHumanMovePredictor]
 * calls it only on the first prediction. Having a factory at all is what makes the explorer
 * "available", so this must stay cheap.
 *
 * Android (LiteRT), iOS (LiteRT C API) and web (LiteRT.js) run the bundled `maia3-5m.tflite`;
 * desktop runs the ONNX export through ONNX Runtime, because LiteRT has no desktop JVM runtime.
 */
internal expect fun maiaRuntimeFactory(): (suspend () -> MaiaRuntime)?

class MaiaHumanMovePredictor(
    private val openRuntime: suspend () -> MaiaRuntime,
    private val top: Int = DEFAULT_TOP_MOVES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : HumanMovePredictor {
    constructor(runtime: MaiaRuntime, top: Int = DEFAULT_TOP_MOVES, dispatcher: CoroutineDispatcher = Dispatchers.Default) :
        this({ runtime }, top, dispatcher)

    private val mutex = Mutex()
    private var runtime: MaiaRuntime? = null
    @kotlin.concurrent.Volatile
    private var closed = false

    override suspend fun predict(position: ExplorerPosition, band: RatingBand): HumanMoveStats? =
        withContext(dispatcher) {
            val legal = MaiaEncoder.legalMoves(position.fen)
            if (legal.isEmpty()) return@withContext null
            val tokens = MaiaEncoder.tokens(position.history)
            // One inference at a time: it keeps peak memory and thread use predictable on phones,
            // and the panel never has more than one position in flight anyway.
            val logits = mutex.withLock {
                check(!closed) { "Maia predictor is closed" }
                val loaded = runtime ?: openRuntime().also { runtime = it }
                try {
                    loaded.moveLogits(tokens, band.maiaRating)
                } finally {
                    // close() arrived mid-inference and could not take the lock: finish its job.
                    if (closed) releaseRuntime()
                }
            }
            require(logits.size == MaiaEncoder.VOCAB) { "Maia returned ${logits.size} logits" }
            // Softmax over the legal moves only, which is what masking the rest to -inf does.
            val max = legal.maxOf { logits[it.first] }
            val weights = legal.map { (index, uci) -> uci to exp((logits[index] - max).toDouble()) }
            val total = weights.sumOf { it.second }
            val state = FenConverter.fenToGameState(position.fen)
            val moves = weights.sortedByDescending { it.second }.take(top).map { (uci, weight) ->
                HumanMove(uci, SanConverter.sanForUci(state, uci) ?: uci, weight / total)
            }
            HumanMoveStats(HumanMoveSource.MAIA, band, moves)
        }

    /** Never blocks: an inference in flight releases the runtime itself when it returns. */
    override fun close() {
        closed = true
        if (mutex.tryLock()) {
            try {
                releaseRuntime()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun releaseRuntime() {
        runtime?.close()
        runtime = null
    }
}

internal const val DEFAULT_TOP_MOVES = 5
