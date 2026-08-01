package com.example.myapplication.movecoach

import com.example.myapplication.MoveRecord
import com.example.myapplication.MoveClass
import kotlin.math.abs

object DeterministicCoach {

    private const val MAX_FALLBACK_CHARS = 300

    fun buildHeadline(record: MoveRecord): String {
        val move = record.san.ifBlank { record.uci }
        val assessment = record.assessment
        
        if (assessment == null) {
            return "Engine choice: $move"
        }

        val className = when (assessment.moveClass) {
            MoveClass.BEST -> "Best move"
            MoveClass.EXCELLENT -> "Excellent"
            MoveClass.GOOD -> "Good"
            MoveClass.INACCURACY -> "Inaccuracy"
            MoveClass.MISTAKE -> "Mistake"
            MoveClass.BLUNDER -> "Blunder"
            MoveClass.BOOK -> "Book move"
        }

        val topMotif = assessment.motifs.firstOrNull()
        val motifText = when (topMotif) {
            "checkmate" -> "delivers checkmate"
            "check" -> "gives check"
            "promotion" -> "promotes"
            "castle-kingside", "castle-queenside" -> "castles"
            "capture" -> "captures material"
            "hangs-piece" -> "hangs a piece"
            "fork" -> "forks"
            "pin" -> "pins"
            "skewer" -> "skewers"
            "discovered-attack" -> "opens a discovered attack"
            else -> null
        }

        return if (motifText != null) {
            "$className — $motifText"
        } else {
            "$className — $move"
        }
    }

    fun buildExplanation(record: MoveRecord): String {
        val assessment = record.assessment
        if (assessment == null) {
            return "It is a standard choice for this position."
        }

        val reason = when {
            assessment.motifs.contains("hangs-piece") -> "It leaves a piece completely undefended."
            assessment.motifs.contains("defends") -> "It defends a piece."
            assessment.motifs.contains("material-swing") -> "It wins material."
            assessment.motifs.contains("recapture") -> "It recaptures, restoring material balance."
            assessment.motifs.contains("threatens") -> "It creates a concrete threat."
            assessment.motifs.contains("fork") -> "It attacks two pieces at once."
            assessment.motifs.contains("pin") -> "It pins an enemy piece against a more valuable one."
            assessment.motifs.contains("skewer") -> "It skewers two pieces on one line."
            assessment.motifs.contains("discovered-attack") -> "It uncovers an attack from the piece behind it."
            assessment.motifs.contains("center-control") -> "It fights for the center."
            assessment.motifs.contains("develops") -> "It develops a piece to an active square."
            assessment.motifs.contains("king-safety") -> "It improves king safety."
            assessment.motifs.contains("castle-kingside") -> "It castles kingside, tucking the king to safety."
            assessment.motifs.contains("castle-queenside") -> "It castles queenside, tucking the king to safety."
            assessment.motifs.contains("pawn-push") -> "It gains space and opens lines."
            else -> {
                val eval = assessment.cpPlayed
                val who = if (eval > 50) "White" else if (eval < -50) "Black" else "Neither side"
                if (abs(eval) > 50) "$who is measurably better after this move."
                else "The position stays roughly balanced."
            }
        }

        val move = record.san.ifBlank { record.uci }
        val finalReason = "Engine choice: $move. $reason"

        return if (finalReason.length <= MAX_FALLBACK_CHARS) finalReason
        else finalReason.take(MAX_FALLBACK_CHARS - 1).trimEnd() + "…"
    }
}
