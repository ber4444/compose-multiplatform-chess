package com.example.myapplication.movecoach

import com.example.myapplication.MoveRecord
import com.example.myapplication.MoveClass
import kotlin.math.abs

object DeterministicCoach {

    /** Motifs that have a headline phrase. Order within a move's motif list decides which wins. */
    private val MOTIF_HEADLINES = mapOf(
        "checkmate" to "delivers checkmate",
        "check" to "gives check",
        "promotion" to "promotes",
        "castle-kingside" to "castles",
        "castle-queenside" to "castles",
        "capture" to "captures material",
        "hangs-piece" to "hangs a piece",
        "fork" to "forks",
        "pin" to "pins",
        "skewer" to "skewers",
        "discovered-attack" to "opens a discovered attack",
    )

    private const val MAX_FALLBACK_CHARS = 300

    fun buildHeadline(record: MoveRecord): String {
        val move = record.san.ifBlank { record.uci }
        val assessment = record.assessment
        
        // No assessment (no engine attached) means no verdict to name, so the move stands alone.
        if (assessment == null) return move

        val className = when (assessment.moveClass) {
            MoveClass.BEST -> "Best move"
            MoveClass.EXCELLENT -> "Excellent"
            MoveClass.GOOD -> "Good"
            MoveClass.INACCURACY -> "Inaccuracy"
            MoveClass.MISTAKE -> "Mistake"
            MoveClass.BLUNDER -> "Blunder"
            MoveClass.BOOK -> "Book move"
        }

        // Scan for the first *recognized* motif rather than taking motifs.first() blindly.
        // buildExplanation uses contains(), so it was order-independent while this was not: a
        // leading unmapped entry — "opening" in the golden set, or whichever tactic MotifDetector
        // happened to append first — silently suppressed the motif and fell through to the bare
        // move. Every headline in the eval set was "Best move — Nf3" for exactly that reason.
        val motifText = assessment.motifs.firstNotNullOfOrNull { MOTIF_HEADLINES[it] }

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

        // No "You played <move>." prefix: the user just played it, the headline names it, and the
        // board tints it. Spending the first third of a 300-char budget restating the input left
        // less room for the only part that is news — the reason.
        val full = listOfNotNull(reason, counterfactual(record)).joinToString(" ")
        return if (full.length <= MAX_FALLBACK_CHARS) full
        else full.take(MAX_FALLBACK_CHARS - 1).trimEnd() + "…"
    }

    /**
     * How much better the best move was, in words, or null when there is nothing to say.
     *
     * This is the sentence the panel was missing. Everything else here describes the move the user
     * just made — which they can see, and whose verdict the board is already colouring — so on a
     * quiet position the whole line degraded to "The position stays roughly balanced.", true and
     * worth nothing. *What to have played instead* is the one thing the user cannot work out by
     * looking, and the engine has been computing it all along: `runIdleAnalysis` asks for the best
     * move to get `cpBest`, stores its UCI, and until now nothing read it.
     *
     * Silent in three cases, each for its own reason:
     * - **No alternative recorded** — no engine attached, or a search that named no move.
     * - **The played move *was* the best one** — `bestMoveSan` is null then by construction, and
     *   "Nf3 was stronger" about the move you just played is nonsense.
     * - **[MoveClass.BEST] or [MoveClass.BOOK]** — within 10cp the difference sits inside the
     *   engine's own noise at these movetimes, and naming an "improvement" the user cannot feel
     *   teaches them to distrust the coach. The boundary is [MoveAssessor]'s own, so this can never
     *   disagree with the class in the headline.
     *
     * The wording escalates with the class rather than quoting a centipawn count — the number is
     * jargon, and `MoveCoachPromptBuilder` already learned that the hard way.
     */
    private fun counterfactual(record: MoveRecord): String? {
        val assessment = record.assessment ?: return null
        val better = assessment.bestMoveSan?.takeIf { it.isNotBlank() } ?: return null
        return when (assessment.moveClass) {
            MoveClass.BEST, MoveClass.BOOK -> null
            MoveClass.EXCELLENT, MoveClass.GOOD -> "$better was a shade sharper."
            MoveClass.INACCURACY -> "$better was stronger."
            MoveClass.MISTAKE -> "$better was much stronger."
            MoveClass.BLUNDER -> "$better would have been far better."
        }
    }
}
