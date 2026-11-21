package com.example.myapplication.explorer

import com.example.myapplication.FenConverter
import com.example.myapplication.SanConverter
import kotlin.math.exp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
    fun moveLogits(tokens: FloatArray, rating: Int): FloatArray
}

/**
 * The per-platform Maia runtime, or `null` where none exists yet. Desktop runs the exported ONNX
 * through ONNX Runtime when `CHESS_MAIA_MODEL` names the file; every other target returns `null`.
 */
internal expect fun createMaiaRuntime(): MaiaRuntime?

class MaiaHumanMovePredictor(
    private val runtime: MaiaRuntime,
    private val top: Int = DEFAULT_TOP_MOVES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : HumanMovePredictor {
    override suspend fun predict(position: ExplorerPosition, band: RatingBand): HumanMoveStats? =
        withContext(dispatcher) {
            val legal = MaiaEncoder.legalMoves(position.fen)
            if (legal.isEmpty()) return@withContext null
            val logits = runtime.moveLogits(MaiaEncoder.tokens(position.history), band.maiaRating)
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

    override fun close() = runtime.close()
}

internal const val DEFAULT_TOP_MOVES = 5
