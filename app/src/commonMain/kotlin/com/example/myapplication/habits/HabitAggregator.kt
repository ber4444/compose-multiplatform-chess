package com.example.myapplication.habits

import com.example.myapplication.FenConverter
import com.example.myapplication.MoveClass
import com.example.myapplication.MoveRecord
import com.example.myapplication.MotifDetector
import com.example.myapplication.persistence.SavedGame

/**
 * Turns saved games into cross-game habits (B6/RAG-5) — the code-detects half of "code detects, the
 * model narrates, never invents". Pure function over data [GameHistoryBackfiller]
 * [com.example.myapplication.persistence.GameHistoryBackfiller] and live play have already computed
 * (`MoveRecord.assessment`); this aggregates it, it does not analyse a position.
 *
 * Two tiers, in order:
 *  1. **A specific costly motif recurring across enough games** (currently just
 *     [MotifDetector.HANGS_PIECE] — the only motif [DeterministicCoach]
 *     [com.example.myapplication.movecoach.DeterministicCoach] treats as describing the player's own
 *     mistake rather than something going right). "You hung a piece in 4 of your last 10 games" is
 *     a sentence worth naming a habit after.
 *  2. **A general mistake/blunder rate**, only reached when no specific motif clears its own bar.
 *     Still true and still actionable, just less specific — "you gave back winning chances in 3 of
 *     your last 10 games" beats saying nothing.
 *
 * Both tiers require the mistake to recur in **multiple games**, not multiple times in one bad
 * game — a single rough game is not a habit.
 */
object HabitAggregator {

    const val DEFAULT_WINDOW = 10

    /** Below this many finished, assessed games there simply isn't enough data to claim a pattern. */
    private const val MIN_GAMES_CONSIDERED = 3

    /** A motif (or the general fallback) must recur in at least this many distinct games to count. */
    private const val MIN_GAMES_FOR_HABIT = 2

    const val MOTIF_EXCESSIVE_HINTS = "excessive-hints"

    /** A player must take at least this many hints in a game for that game to count as hint-reliant. */
    const val MIN_HINTS_PER_GAME_FOR_HABIT = 2

    private const val MAX_HABITS = 2
    private const val MAX_OCCURRENCES_PER_HABIT = 5

    /**
     * Motifs on a MISTAKE/BLUNDER-class move that describe the player's *own* mistake rather than
     * something going right on the position. Mirrors `DeterministicCoach.COSTLY_MOTIFS` — kept as a
     * separate declaration because that one is `private` (published-API and ownership reasons don't
     * apply here, it's just not exposed), not because the two are meant to diverge.
     */
    private val COSTLY_MOTIFS = listOf(MotifDetector.HANGS_PIECE)

    private data class Flagged(
        val game: SavedGame,
        val gamesAgo: Int,
        val plyIndex: Int,
        val record: MoveRecord,
    )

    private data class FlaggedHint(
        val game: SavedGame,
        val gamesAgo: Int,
        val plyIndex: Int,
        val record: MoveRecord,
    )

    /**
     * The player's habits across the most recent [window] saved games (newest first, matching
     * [com.example.myapplication.persistence.GameHistoryRepository.games]), displaying tactical
     * habits (e.g. hangs-piece, general lost winning chances) and behavioral habits (excessive hints)
     * alongside each other as part of the same player profile. Returns `emptyList()` when there
     * isn't enough data or no pattern recurs.
     */
    fun aggregate(games: List<SavedGame>, window: Int = DEFAULT_WINDOW): List<HabitSummary> {
        val considered = games.take(window)
        if (considered.size < MIN_GAMES_CONSIDERED) return emptyList()

        val flagged = considered.flatMapIndexed { gamesAgo, game ->
            val playerIsWhite = game.playerSide != "BLACK"
            game.moveRecords.mapIndexedNotNull { index, record ->
                val isPlayerMove = (index % 2 == 0) == playerIsWhite
                val assessment = record.assessment
                if (isPlayerMove && assessment != null &&
                    (assessment.moveClass == MoveClass.MISTAKE || assessment.moveClass == MoveClass.BLUNDER)
                ) {
                    Flagged(game, gamesAgo, index, record)
                } else {
                    null
                }
            }
        }

        val tacticalHabits = if (flagged.isNotEmpty()) {
            val motifHabits = COSTLY_MOTIFS.mapNotNull { motif ->
                val matches = flagged.filter { motif in it.record.assessment!!.motifs }
                summaryOrNull(motif, matches, considered.size)
            }.sortedByDescending { it.gamesAffected }

            if (motifHabits.isNotEmpty()) {
                motifHabits.take(MAX_HABITS)
            } else {
                listOfNotNull(summaryOrNull(motif = null, matches = flagged, gamesConsidered = considered.size))
            }
        } else {
            emptyList()
        }

        // Behavioral aggregation: excessive hint taking
        val flaggedHints = considered.flatMapIndexed { gamesAgo, game ->
            val playerIsWhite = game.playerSide != "BLACK"
            game.moveRecords.mapIndexedNotNull { index, record ->
                val isPlayerMove = (index % 2 == 0) == playerIsWhite
                if (isPlayerMove && record.hintUsed) {
                    FlaggedHint(game, gamesAgo, index, record)
                } else {
                    null
                }
            }
        }

        val hintsByGame = flaggedHints.groupBy { it.game.id }
        val qualifyingHintGames = hintsByGame.filter { it.value.size >= MIN_HINTS_PER_GAME_FOR_HABIT }
        val behavioralHabits = if (qualifyingHintGames.size >= MIN_GAMES_FOR_HABIT) {
            val occurrences = qualifyingHintGames.values.flatten()
                .sortedWith(compareBy<FlaggedHint> { it.gamesAgo }.thenBy { it.plyIndex })
                .take(MAX_OCCURRENCES_PER_HABIT)
                .map { it.toOccurrence() }
            listOf(
                HabitSummary(
                    motif = MOTIF_EXCESSIVE_HINTS,
                    gamesAffected = qualifyingHintGames.size,
                    gamesConsidered = considered.size,
                    occurrences = occurrences,
                    category = HabitCategory.BEHAVIORAL,
                )
            )
        } else {
            emptyList()
        }

        return tacticalHabits + behavioralHabits
    }

    private fun summaryOrNull(motif: String?, matches: List<Flagged>, gamesConsidered: Int): HabitSummary? {
        if (matches.isEmpty()) return null
        val gamesAffected = matches.map { it.game.id }.distinct().size
        if (gamesAffected < MIN_GAMES_FOR_HABIT) return null

        val occurrences = matches
            .sortedWith(compareBy<Flagged> { it.gamesAgo }.thenByDescending { it.record.assessment!!.cpLoss })
            .take(MAX_OCCURRENCES_PER_HABIT)
            .map { it.toOccurrence() }

        return HabitSummary(motif, gamesAffected, gamesConsidered, occurrences, HabitCategory.TACTICAL)
    }

    private fun Flagged.toOccurrence(): HabitOccurrence {
        val assessment = record.assessment!!
        return HabitOccurrence(
            gameId = game.id,
            gameResult = game.result,
            gamesAgo = gamesAgo,
            plyNumber = plyIndex + 1,
            san = record.san,
            fenBefore = fenBefore(game, plyIndex),
            moveClass = assessment.moveClass,
            cpLoss = assessment.cpLoss,
            bestMoveSan = assessment.bestMoveSan,
            isHint = false,
        )
    }

    private fun FlaggedHint.toOccurrence(): HabitOccurrence {
        return HabitOccurrence(
            gameId = game.id,
            gameResult = game.result,
            gamesAgo = gamesAgo,
            plyNumber = plyIndex + 1,
            san = record.san,
            fenBefore = fenBefore(game, plyIndex),
            moveClass = record.assessment?.moveClass,
            cpLoss = record.assessment?.cpLoss ?: 0,
            bestMoveSan = record.assessment?.bestMoveSan,
            isHint = true,
        )
    }

    private fun fenBefore(game: SavedGame, plyIndex: Int): String =
        if (plyIndex == 0) FenConverter.STARTING_FEN else game.moveRecords[plyIndex - 1].fenAfter
}
