package com.example.myapplication

import kotlin.math.max

object MoveAssessor {

    /**
     * Assesses a move by [mover] from the evaluation before it, the evaluation after the move
     * played, the evaluation of the best move, and any tactical motifs detected.
     *
     * Every score is from **White's perspective**, as every engine transport reports it and as
     * [MoveAssessment] stores it. [mover] is what turns that into a loss: a Black move that costs
     * Black raises the score, so the loss is `cpPlayed - cpBest` for Black and `cpBest - cpPlayed`
     * for White. Without it, every Black blunder graded as BEST.
     */
    fun assessMove(
        mover: Set,
        cpBefore: Int,
        cpPlayed: Int,
        cpBest: Int,
        motifs: List<String> = emptyList(),
        bestMoveUci: String? = null,
        bestMoveSan: String? = null,
        motifDetails: Map<String, String> = emptyMap(),
        bestMoveMotifs: List<String> = emptyList(),
        bestMoveMotifDetails: Map<String, String> = emptyMap(),
    ): MoveAssessment {
        val cpLoss = cpLoss(mover, cpPlayed = cpPlayed, cpBest = cpBest)

        return MoveAssessment(
            cpBefore = cpBefore,
            cpPlayed = cpPlayed,
            cpBest = cpBest,
            cpLoss = cpLoss,
            moveClass = classifyMove(cpLoss),
            motifs = motifs,
            bestMoveUci = bestMoveUci,
            bestMoveSan = bestMoveSan,
            motifDetails = motifDetails,
            bestMoveMotifs = bestMoveMotifs,
            bestMoveMotifDetails = bestMoveMotifDetails,
        )
    }

    /**
     * The White-only form this function had before it took the mover, kept so the published
     * `chess-core` API stays source- and binary-compatible. It is only right for White's moves.
     */
    @Deprecated(
        "Grades every move as White's, so a Black blunder scores as BEST. Pass the side that moved.",
        ReplaceWith("assessMove(Set.WHITE, cpBefore, cpPlayed, cpBest, motifs, bestMoveUci, bestMoveSan, motifDetails, bestMoveMotifs, bestMoveMotifDetails)"),
    )
    fun assessMove(
        cpBefore: Int,
        cpPlayed: Int,
        cpBest: Int,
        motifs: List<String> = emptyList(),
        bestMoveUci: String? = null,
        bestMoveSan: String? = null,
        motifDetails: Map<String, String> = emptyMap(),
        bestMoveMotifs: List<String> = emptyList(),
        bestMoveMotifDetails: Map<String, String> = emptyMap(),
    ): MoveAssessment = assessMove(
        Set.WHITE, cpBefore, cpPlayed, cpBest, motifs, bestMoveUci, bestMoveSan, motifDetails,
        bestMoveMotifs, bestMoveMotifDetails,
    )

    /**
     * [record] with its loss and class recomputed from the scores it already stores, for the side
     * that played it.
     *
     * Records saved before [assessMove] took the mover graded Black's moves as White's, which made
     * nearly every Black move BEST. The raw scores were stored correctly, so the grade can be
     * recomputed exactly, with no engine. Applied where saved records are read back, it is a no-op
     * for White's moves and for anything already graded correctly, so it is safe to run on every
     * load.
     *
     * The mover is read off [MoveRecord.fenAfter] (after Black moves, White is to move) rather than
     * from a ply index, so it needs nothing but the record. A record whose FEN names no side, or
     * that has no assessment, is returned as it is. [MoveClass.BOOK] is left alone: it is not a
     * centipawn verdict.
     */
    fun regrade(record: MoveRecord): MoveRecord {
        val assessment = record.assessment ?: return record
        if (assessment.moveClass == MoveClass.BOOK) return record
        val mover = when (record.fenAfter.split(' ').getOrNull(1)) {
            "w" -> Set.BLACK
            "b" -> Set.WHITE
            else -> return record
        }
        val cpLoss = cpLoss(mover, cpPlayed = assessment.cpPlayed, cpBest = assessment.cpBest)
        val moveClass = classifyMove(cpLoss)
        if (cpLoss == assessment.cpLoss && moveClass == assessment.moveClass) return record
        return record.copy(assessment = assessment.copy(cpLoss = cpLoss, moveClass = moveClass))
    }

    /**
     * How much worse the played move is than the best one, for [mover]. Non-negative in theory, but
     * the two scores come from separate searches and the played move can come out ahead, so it is
     * clamped at zero.
     */
    private fun cpLoss(mover: Set, cpPlayed: Int, cpBest: Int): Int =
        max(0, if (mover == Set.WHITE) cpBest - cpPlayed else cpPlayed - cpBest)

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
