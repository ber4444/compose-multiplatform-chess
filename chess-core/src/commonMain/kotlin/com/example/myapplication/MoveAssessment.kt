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
)

@Serializable
enum class MoveClass {
    BEST, EXCELLENT, GOOD, INACCURACY, MISTAKE, BLUNDER, BOOK
}
