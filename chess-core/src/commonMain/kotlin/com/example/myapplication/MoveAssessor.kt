package com.example.myapplication

import kotlin.math.abs
import kotlin.math.max

object MoveAssessor {

    /**
     * Assesses a move based on the evaluation before the move, the evaluation of the move played,
     * the evaluation of the best move, and any tactical motifs detected.
     */
    fun assessMove(
        cpBefore: Int,
        cpPlayed: Int,
        cpBest: Int,
        motifs: List<String> = emptyList(),
        bestMoveUci: String? = null,
        bestMoveSan: String? = null,
    ): MoveAssessment {
        // The loss is how much worse the played move is compared to the best possible move.
        // It's strictly non-negative in theory, but due to search depth variations it might be slightly negative.
        val cpLoss = max(0, cpBest - cpPlayed)

        val moveClass = classifyMove(cpLoss)

        return MoveAssessment(
            cpBefore = cpBefore,
            cpPlayed = cpPlayed,
            cpBest = cpBest,
            cpLoss = cpLoss,
            moveClass = moveClass,
            motifs = motifs,
            bestMoveUci = bestMoveUci,
            bestMoveSan = bestMoveSan,
        )
    }

    private fun classifyMove(cpLoss: Int): MoveClass {
        return when {
            cpLoss <= 10 -> MoveClass.BEST
            cpLoss <= 30 -> MoveClass.EXCELLENT
            cpLoss <= 60 -> MoveClass.GOOD
            cpLoss <= 100 -> MoveClass.INACCURACY
            cpLoss <= 300 -> MoveClass.MISTAKE
            else -> MoveClass.BLUNDER
        }
    }
}
