package com.example.myapplication.movecoach

import com.example.myapplication.MoveAssessment
import com.example.myapplication.MoveRecord
import com.example.myapplication.MoveClass
import com.example.myapplication.MotifDetector
import com.example.myapplication.Set
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
        "recapture" to "recaptures",
        "material-swing" to "wins material",
        "develops" to "develops a piece",
        "center-control" to "takes the centre",
        "king-safety" to "walks the king to safety",
        "pawn-push" to "pushes a pawn",
        "defends" to "shores up the defence",
        "threatens" to "creates a threat",
    )

    /**
     * Motifs in `MotifDetector.ALL_MOTIFS` order, so the headline and the explanation are chosen by
     * one declared priority instead of two.
     *
     * Sorted here rather than relied on from the caller. `detectMotifs` does return them ordered,
     * but assessments persisted before that existed do not, nor does a hand-built list from a test
     * or from the React Native consumer — and an unordered list silently picks whichever motif
     * happens to be first, which is how "Best move — develops a piece | It fights for the center."
     * (a line disagreeing with itself) appeared. Unknown motifs sort last rather than first.
     */
    /**
     * Motifs on a mistake that are still worth saying — i.e. the ones that explain the *damage*.
     *
     * A move can win a pawn and lose the game. The panel showed "It wins the pawn on g6." beside a
     * **red** square, which is the same contradiction as praising a move the board is scolding: the
     * colour says you erred and the sentence congratulates you, and the reader learns nothing about
     * what went wrong. Reported from a real game.
     *
     * So once the engine says the move cost something, only the motifs that describe a cost may
     * speak. Everything else falls through to the evaluation sentence and the counterfactual, which
     * are about the mistake and are what the player actually needs. On a move the board is not
     * scolding, every motif is fair game.
     */
    private fun explanatoryMotifs(assessment: MoveAssessment): List<String> = when (assessment.moveClass) {
        MoveClass.BEST, MoveClass.EXCELLENT, MoveClass.GOOD, MoveClass.BOOK -> assessment.motifs
        MoveClass.INACCURACY, MoveClass.MISTAKE, MoveClass.BLUNDER ->
            assessment.motifs.filter { it in COSTLY_MOTIFS }
    }

    /** The motifs that describe something going wrong, rather than something going right. */
    private val COSTLY_MOTIFS = setOf(MotifDetector.HANGS_PIECE)

    /**
     * Who moved, from the FEN after the move — `getOrNull` because a record may carry no FEN at all.
     * Only a fallback; prefer passing the side explicitly.
     */
    private fun MoveRecord.inferMoverSide(): Set =
        if (fenAfter.split(" ").getOrNull(1) == "w") Set.BLACK else Set.WHITE

    private fun byPriority(motifs: List<String>): List<String> =
        motifs.sortedBy { motif ->
            MotifDetector.ALL_MOTIFS.indexOf(motif).let { if (it < 0) Int.MAX_VALUE else it }
        }

    /**
     * Motif to sentence, **most newsworthy first** — the first entry whose motif is present wins.
     *
     * A declared table rather than a `when` chain so [handledMotifs] can be derived from it. The
     * chain version could not be inspected, so nothing could check that the motifs it branched on
     * were ones anything actually emitted — and eleven of them were not, leaving the coach with
     * sentences it could never reach and a fallthrough that read "The position stays roughly
     * balanced." on any ordinary move.
     *
     * **Unordered on purpose.** Priority lives in `MotifDetector.ALL_MOTIFS` and nowhere else;
     * [buildExplanation] and [buildHeadline] both scan the record's motif list, which `detectMotifs`
     * has already sorted by it.
     */
    private val MOTIF_EXPLANATIONS: Map<String, String> = mapOf(
        "checkmate" to "It ends the game — that's checkmate.",
        "promotion" to "It promotes the pawn into a new piece.",
        "hangs-piece" to "It leaves a piece completely undefended.",
        "defends" to "It defends a piece.",
        "material-swing" to "It wins material.",
        "recapture" to "It recaptures, restoring material balance.",
        "capture" to "It takes a piece off the board.",
        "check" to "It puts the enemy king in check.",
        "threatens" to "It creates a concrete threat.",
        "fork" to "It attacks two pieces at once.",
        "pin" to "It pins an enemy piece against a more valuable one.",
        "skewer" to "It skewers two pieces on one line.",
        "discovered-attack" to "It uncovers an attack from the piece behind it.",
        "center-control" to "It fights for the center.",
        "develops" to "It develops a piece to an active square.",
        "king-safety" to "It improves king safety.",
        "castle-kingside" to "It castles kingside, tucking the king to safety.",
        "castle-queenside" to "It castles queenside, tucking the king to safety.",
        "pawn-push" to "It gains space and opens lines.",
    )

    /**
     * Every motif this coach has words for, headline or explanation.
     *
     * Derived from the two tables so it cannot drift from them. `MotifDetectorTest` asserts this is
     * a subset of `MotifDetector.ALL_MOTIFS` — the check that was missing while eleven of these
     * were unreachable.
     */
    fun handledMotifs(): kotlin.collections.Set<String> =
        MOTIF_EXPLANATIONS.keys + MOTIF_HEADLINES.keys

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
        val motifText = byPriority(assessment.motifs).firstNotNullOfOrNull { MOTIF_HEADLINES[it] }

        return if (motifText != null) {
            "$className — $motifText"
        } else {
            "$className — $move"
        }
    }

    /**
     * [playerSide] is passed in, not inferred.
     *
     * It used to be read off `fenAfter`'s side-to-move field, which crashed with
     * `IndexOutOfBoundsException` the moment a record carried a blank or malformed FEN — and every
     * `MoveRecord` built in a test does. It was also only *accidentally* right: that field says who
     * moves next, which is the mover's opponent, and equals the player only because the coach
     * happens to run on player moves. `MoveCoachManager` has `gameViewModel.playerSide` and can
     * simply say so.
     *
     * The default keeps the old inference for callers that have no side to give, but reads the FEN
     * defensively so a missing field degrades to White instead of throwing.
     */
    fun buildExplanation(record: MoveRecord, playerSide: Set = record.inferMoverSide()): String {
        val assessment = record.assessment
        if (assessment == null) {
            return "It is a standard choice for this position."
        }

        // The specific sentence wins over the definition: "Your bishop on b5 pins the knight on c6
        // against the queen on d8." rather than "It pins an enemy piece against a more valuable one."
        val reason = byPriority(explanatoryMotifs(assessment))
            .firstNotNullOfOrNull { assessment.motifDetails[it] ?: MOTIF_EXPLANATIONS[it] }
            ?: run {
                val winLost = assessment.winPercentLost(playerSide)
                if (winLost >= 1.0) {
                    "It drops your winning chances by ${winLost.toInt()}%."
                } else {
                    "It is a solid, positional move."
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
            // Silent for everything the board paints green. "Be2 was a shade sharper." next to a
            // green square tells the user they did well and then immediately takes it back, over a
            // gap of at most 60cp that no human can feel. The counterfactual only earns its place
            // once the move actually cost something — which is exactly when the board stops being
            // green, so the sentence and the colour now say the same thing.
            MoveClass.BEST, MoveClass.EXCELLENT, MoveClass.GOOD, MoveClass.BOOK -> null
            MoveClass.INACCURACY, MoveClass.MISTAKE, MoveClass.BLUNDER -> {
                val bestMotifText = byPriority(assessment.bestMoveMotifs)
                    .firstNotNullOfOrNull { assessment.bestMoveMotifDetails[it] ?: MOTIF_EXPLANATIONS[it] }

                val suffix = if (bestMotifText != null) {
                    val lower = bestMotifText.replaceFirstChar { it.lowercase() }
                    " — ${lower.trimEnd('.')}"
                } else ""

                val base = when (assessment.moveClass) {
                    MoveClass.INACCURACY -> "$better was stronger"
                    MoveClass.MISTAKE -> "$better was much stronger"
                    MoveClass.BLUNDER -> "$better would have been far better"
                    else -> "" // Unreachable due to the outer when, but required by compiler if exhaustive check is weird
                }

                "$base$suffix."
            }
        }
    }
}
