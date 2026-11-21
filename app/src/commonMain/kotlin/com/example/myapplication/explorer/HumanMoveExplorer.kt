package com.example.myapplication.explorer

import com.example.myapplication.multiplayer.LichessException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why the answer did not come from Lichess. `null` on an answer means it did. */
enum class LichessMiss { NOT_SIGNED_IN, OFFLINE, RATE_LIMITED, UNAUTHORIZED, ERROR, NO_DATA }

sealed interface ExplorerAnswer {
    data class Moves(val stats: HumanMoveStats, val lichessMiss: LichessMiss? = null) : ExplorerAnswer

    /** Neither source could answer. [lichessMiss] is `null` when this build has no Lichess. */
    data class NoAnswer(val lichessMiss: LichessMiss?) : ExplorerAnswer
}

/**
 * "What do players at this level play here?" — the Lichess explorer when it can answer, Maia-3 when
 * it cannot.
 *
 * Lichess goes first because it is real games, not a model's guess. It cannot answer when the user
 * is not signed in (the explorer needs a token), when it is unreachable or refuses, or — the common
 * case past the opening — when fewer than [minLichessGames] games in the band reached the position.
 * Each of those falls through to [maia] where one exists, and the answer carries the reason so the
 * UI can say why it is showing a prediction. With no Maia on this platform, a thin Lichess result is
 * shown rather than nothing; an empty one is a [ExplorerAnswer.NoAnswer].
 *
 * [lichess] is `null` when the build has no Lichess integration at all; [lichessToken] returns the
 * signed-in session's token or `null`.
 */
class HumanMoveExplorer(
    private val lichess: LichessOpeningExplorer?,
    private val maia: HumanMovePredictor?,
    private val lichessToken: () -> String?,
    private val minLichessGames: Long = MIN_LICHESS_GAMES,
) : AutoCloseable {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Pair<List<String>, RatingBand>, ExplorerAnswer>()

    /**
     * Whether *some* source can answer now. The History panel is not rendered otherwise: with
     * neither Maia nor a signed-in Lichess session it could only ever say "unavailable".
     */
    fun canAnswer(): Boolean = maia != null || (lichess != null && lichessToken() != null)

    suspend fun explore(position: ExplorerPosition, band: RatingBand): ExplorerAnswer {
        val token = lichessToken()
        // The cache key includes whether we are signed in, so signing in re-asks Lichess.
        val key = (position.history + (token != null).toString()) to band
        mutex.withLock { cache[key] }?.let { return it }
        val answer = compute(position, band, token)
        // Transient failures are not cached: the next step back to this ply should retry.
        val miss = when (answer) {
            is ExplorerAnswer.Moves -> answer.lichessMiss
            is ExplorerAnswer.NoAnswer -> answer.lichessMiss
        }
        if (miss.isCacheable()) {
            mutex.withLock {
                cache[key] = answer
                if (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
            }
        }
        return answer
    }

    private suspend fun compute(position: ExplorerPosition, band: RatingBand, token: String?): ExplorerAnswer {
        var thin: HumanMoveStats? = null
        val miss: LichessMiss? = when {
            lichess == null -> null
            token == null -> LichessMiss.NOT_SIGNED_IN
            else -> try {
                val stats = lichess.lookup(position.fen, band, token)
                if ((stats.totalGames ?: 0) >= minLichessGames && stats.moves.isNotEmpty()) {
                    return ExplorerAnswer.Moves(stats)
                }
                thin = stats.takeIf { it.moves.isNotEmpty() }
                LichessMiss.NO_DATA
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LichessException) {
                when (failure.status) {
                    429 -> LichessMiss.RATE_LIMITED
                    401, 403 -> LichessMiss.UNAUTHORIZED
                    else -> LichessMiss.ERROR
                }
            } catch (_: Exception) {
                LichessMiss.OFFLINE
            }
        }
        val predicted = maia?.let {
            try {
                it.predict(position, band)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        return when {
            predicted != null -> ExplorerAnswer.Moves(predicted, miss)
            thin != null -> ExplorerAnswer.Moves(thin, null)
            else -> ExplorerAnswer.NoAnswer(miss)
        }
    }

    override fun close() {
        maia?.close()
    }

    private fun LichessMiss?.isCacheable() = this == null || this == LichessMiss.NO_DATA || this == LichessMiss.NOT_SIGNED_IN
}

/**
 * The explorer the History screen may show, or `null`: never while assistance is blocked (a live
 * Lichess game), and never when no source could answer — the availability rule, so the panel is
 * absent rather than permanently "unavailable".
 */
fun HumanMoveExplorer.forReview(assistanceBlocked: Boolean): HumanMoveExplorer? =
    takeIf { !assistanceBlocked && it.canAnswer() }

/** Below this, a Lichess percentage is a handful of games and Maia's estimate is the better answer. */
internal const val MIN_LICHESS_GAMES = 50L
private const val CACHE_SIZE = 128
