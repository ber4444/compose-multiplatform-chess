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
    val bestMoveUci: String? = null
)

@Serializable
enum class MoveClass {
    BEST, EXCELLENT, GOOD, INACCURACY, MISTAKE, BLUNDER, BOOK
}
