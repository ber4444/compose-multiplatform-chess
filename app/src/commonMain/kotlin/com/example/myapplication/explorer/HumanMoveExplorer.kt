package com.example.myapplication.explorer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ExplorerAnswer {
    data class Moves(val stats: HumanMoveStats) : ExplorerAnswer

    /** No source could answer (the game was over in this position, or the model failed). */
    data object NoAnswer : ExplorerAnswer
}

/**
 * "What do players at this level play here?", answered by Maia-3's prediction for a rating band.
 *
 * [maia] is `null` where the platform has no Maia runtime, and then [canAnswer] is false and the
 * History panel is not shown at all. [maia] is not owned here: `AppRoot` shares one predictor (one
 * model load) with the Maia opponent and closes it. Answers are cached per (history, band), so
 * stepping back and forth through a game does not re-run the model.
 */
class HumanMoveExplorer(private val maia: HumanMovePredictor?) {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Pair<List<String>, RatingBand>, ExplorerAnswer>()

    /** Whether some source can answer. The History panel is not rendered otherwise. */
    fun canAnswer(): Boolean = maia != null

    suspend fun explore(position: ExplorerPosition, band: RatingBand): ExplorerAnswer {
        val key = position.history to band
        mutex.withLock { cache[key] }?.let { return it }
        val predicted = maia?.let {
            try {
                it.predict(position, band)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        val answer = predicted?.let { ExplorerAnswer.Moves(it) } ?: ExplorerAnswer.NoAnswer
        // A failure is not cached: the next step back to this ply should retry.
        if (predicted != null) {
            mutex.withLock {
                cache[key] = answer
                if (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
            }
        }
        return answer
    }
}

/**
 * The explorer the History screen may show, or `null` when no source could answer: the
 * availability rule, so the panel is absent rather than permanently "unavailable".
 */
fun HumanMoveExplorer.forReview(): HumanMoveExplorer? = takeIf { it.canAnswer() }

private const val CACHE_SIZE = 128
