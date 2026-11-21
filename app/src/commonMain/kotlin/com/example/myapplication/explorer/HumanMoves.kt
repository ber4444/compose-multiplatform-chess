package com.example.myapplication.explorer

import com.example.myapplication.EngineDifficulty
import com.example.myapplication.FenConverter
import com.example.myapplication.MoveRecord

/** Where a [HumanMoveStats] came from. The UI names the source on every answer. */
enum class HumanMoveSource { LICHESS, MAIA }

/**
 * One move humans play in a position. [share] is the fraction of games (Lichess) or the model's
 * probability (Maia), in 0..1. [games] is only known for Lichess.
 */
data class HumanMove(val uci: String, val san: String, val share: Double, val games: Long? = null)

data class HumanMoveStats(
    val source: HumanMoveSource,
    val band: RatingBand,
    val moves: List<HumanMove>,
    /** Games that reached the position in this band (Lichess only). */
    val totalGames: Long? = null,
)

/**
 * A position to explore: its FEN plus the positions that led to it, oldest first, ending with
 * [fen]. Maia-3 conditions on up to eight positions of history; Lichess only needs [fen].
 */
data class ExplorerPosition(val fen: String, val history: List<String> = listOf(fen)) {
    init {
        require(history.lastOrNull() == fen) { "history must end with the position itself" }
    }

    companion object {
        /**
         * The position *before* ply [plyIndex] of a recorded game, with its history. Recorded games
         * here always start from the standard position (solo games are the only ones with
         * move records).
         */
        fun beforePly(records: List<MoveRecord>, plyIndex: Int): ExplorerPosition {
            require(plyIndex in records.indices) { "ply $plyIndex outside 0..${records.lastIndex}" }
            val fens = listOf(FenConverter.STARTING_FEN) + records.take(plyIndex).map { it.fenAfter }
            return ExplorerPosition(fens.last(), fens.takeLast(MAIA_HISTORY))
        }
    }
}

/**
 * A rating band, aligned to the Lichess opening explorer's rating groups so both sources answer the
 * same question. Each group runs from [floor] to the next group's floor minus one; the top group is
 * open-ended. Maia is asked at [maiaRating], a representative rating inside the group.
 */
enum class RatingBand(val floor: Int, val maiaRating: Int) {
    UNDER_1000(0, 800),
    R1000(1000, 1100),
    R1200(1200, 1300),
    R1400(1400, 1500),
    R1600(1600, 1700),
    R1800(1800, 1900),
    R2000(2000, 2100),
    R2200(2200, 2350),
    R2500(2500, 2600);

    val label: String
        get() = when (this) {
            UNDER_1000 -> "<1000"
            R2500 -> "2500+"
            else -> "$floor–${entries[ordinal + 1].floor - 1}"
        }

    companion object {
        fun forRating(rating: Int): RatingBand = entries.last { rating >= it.floor }

        /**
         * Used when no Lichess rating is known. The engine difficulty a player picks is the best
         * signal this app has of their level without asking: Easy and Max sit near the ends of the
         * club range, and Medium (the default) at 1400–1599, the most common Lichess rapid band.
         */
        fun forDifficulty(difficulty: EngineDifficulty): RatingBand = when (difficulty) {
            EngineDifficulty.EASY -> R1000
            EngineDifficulty.MEDIUM -> R1400
            EngineDifficulty.HARD -> R1800
            EngineDifficulty.MAX -> R2200
        }
    }
}

internal const val MAIA_HISTORY = 8
