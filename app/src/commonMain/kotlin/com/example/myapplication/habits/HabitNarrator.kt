package com.example.myapplication.habits

import com.example.myapplication.MotifDetector

/**
 * Turns a [HabitSummary] into display text.
 *
 * Deterministic, same reasoning as
 * [DeterministicCoach][com.example.myapplication.movecoach.DeterministicCoach]: Android ships no
 * on-device model (see `android-model-latency-2026-08.md`), so this text is the whole product here,
 * not a fallback standing in for one. A model, where one exists, could later rephrase [headline]/
 * [explanation] — it must never be asked to invent the counts or the motif, which is exactly why
 * they're computed by [HabitAggregator] rather than left for a prompt to reconstruct.
 */
object HabitNarrator {

    /** Only [HabitAggregator]'s `COSTLY_MOTIFS` ever reach here, so this only needs their phrasing. */
    private val MOTIF_PHRASES = mapOf(
        MotifDetector.HANGS_PIECE to "hung a piece",
    )

    fun headline(summary: HabitSummary): String {
        val verb = summary.motif?.let { MOTIF_PHRASES[it] } ?: "given back winning chances"
        return "You've $verb in ${summary.gamesAffected} of your last ${summary.gamesConsidered} games."
    }

    fun explanation(summary: HabitSummary): String = when (summary.motif) {
        MotifDetector.HANGS_PIECE ->
            "A piece was left undefended and lost for nothing — here's where it happened:"
        else ->
            "These moves gave back significant winning chances, without one specific tactic behind all of them:"
    }

    /** One line per [HabitOccurrence], for a practice list. */
    fun occurrenceLine(occurrence: HabitOccurrence): String {
        val recency = when (occurrence.gamesAgo) {
            0 -> "your most recent game"
            1 -> "the game before that"
            else -> "${occurrence.gamesAgo} games ago"
        }
        val moveNumber = (occurrence.plyNumber - 1) / 2 + 1
        val isBlackMove = (occurrence.plyNumber - 1) % 2 == 1
        val moveLabel = if (isBlackMove) "$moveNumber...${occurrence.san}" else "$moveNumber.${occurrence.san}"
        val better = occurrence.bestMoveSan?.let { " — $it kept the position level" } ?: ""
        return "In $recency, $moveLabel$better."
    }
}
