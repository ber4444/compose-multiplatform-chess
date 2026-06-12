package com.example.myapplication

import kotlin.math.abs
import kotlin.math.sign

object UciEvaluation {
    const val MATE_SCORE_CP = 100_000

    fun parseInfoScore(line: String): Int? {
        if (!line.startsWith("info ")) return null
        
        val tokens = line.split("\\s+".toRegex())
        val scoreIndex = tokens.indexOf("score")
        if (scoreIndex != -1 && scoreIndex + 2 < tokens.size) {
            val type = tokens[scoreIndex + 1]
            val value = tokens[scoreIndex + 2].toIntOrNull() ?: return null
            return when (type) {
                "cp" -> value
                "mate" -> mateToCp(value)
                else -> null
            }
        }
        return null
    }

    fun mateToCp(matePlies: Int): Int {
        if (matePlies == 0) return -MATE_SCORE_CP
        return sign(matePlies.toFloat()).toInt() * (MATE_SCORE_CP - abs(matePlies))
    }

    fun isWhiteToMove(fen: String): Boolean {
        val parts = fen.split("\\s+".toRegex())
        if (parts.size >= 2) {
            return parts[1] != "b"
        }
        return true
    }

    fun toWhitePerspective(scoreCp: Int, whiteToMove: Boolean): Int {
        return if (whiteToMove) scoreCp else -scoreCp
    }
}
