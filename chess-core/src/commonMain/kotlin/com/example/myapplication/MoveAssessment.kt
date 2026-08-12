package com.example.myapplication

import kotlinx.serialization.Serializable

@Serializable
data class MoveAssessment(
    val cpBefore: Int,
    val cpPlayed: Int,
    val cpBest: Int,
    val cpLoss: Int,
    val moveClass: MoveClass,
    val motifs: List<String>,
    val bestMoveUci: String? = null,
    /**
     * [bestMoveUci] as SAN, resolved at analysis time because that is the only place the position
     * before the move is at hand. Carried rather than re-derived so the coach, the summary and a
     * replayed history entry all name the same alternative without re-running the converter.
     *
     * Additive with a default, so snapshots written before this field still deserialize.
     */
    val bestMoveSan: String? = null,
    /**
     * Motif name to a sentence naming the actual pieces and squares involved, where the detector
     * had them.
     *
     * `MOTIF_EXPLANATIONS` in `DeterministicCoach` holds *definitions* — "It pins an enemy piece
     * against a more valuable one." is what a pin **is**, not what happened on this board, and it
     * reads as a textbook glossary entry rather than as coaching. The detector already computes the
     * attacker, the pinned piece and the piece behind it in order to decide the motif at all, and
     * then discarded every one of them.
     *
     * Sparse: only motifs with something specific to say appear, and the coach falls back to the
     * definition for the rest. Additive with a default, so snapshots written before this still
     * deserialize.
     */
    val motifDetails: Map<String, String> = emptyMap(),
    val bestMoveMotifs: List<String> = emptyList(),
    val bestMoveMotifDetails: Map<String, String> = emptyMap(),
) {
    fun winPercentBefore(playerSide: Set): Double = UciEvaluation.winPercent(if (playerSide == Set.WHITE) cpBefore else -cpBefore)
    fun winPercentAfter(playerSide: Set): Double = UciEvaluation.winPercent(if (playerSide == Set.WHITE) cpPlayed else -cpPlayed)
    fun winPercentLost(playerSide: Set): Double = winPercentBefore(playerSide) - winPercentAfter(playerSide)
}

@Serializable
enum class MoveClass {
    BEST, EXCELLENT, GOOD, INACCURACY, MISTAKE, BLUNDER, BOOK
}
