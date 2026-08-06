package com.example.coachserver

/**
 * One sentence about **this specific line**, derived from its own moves.
 *
 * [EcoNarrator] characterizes an ECO *range*, which means every row sharing a code gets the same
 * opening sentence. Retrieval for 1.e4 c5 returns four B20 passages — `Sicilian Defense`,
 * `King David's Opening`, `Myers Attack, with h4` — whose texts are identical up to the name, and
 * because both composers quote the **first sentence of the top passage**, the user sees one claim
 * no matter which line they played. A hand-review of ten openings (2026-08-05) rejected the output
 * on exactly this: correct, cited, grounded, and the same words every time.
 *
 * Placement is the whole mechanism. A distinguishing sentence anywhere but first would be invisible.
 *
 * ## What this may say
 *
 * **Only what the move list itself proves.** There is no board here, no engine, and no source of
 * chess judgement — just SAN. So: an early king move forfeits castling (the rules say so), a bishop
 * reached a long diagonal after a flank pawn move (the squares say so), a capture happened on move
 * N (the `x` says so).
 *
 * It may **never** evaluate. Not "Black is comfortable", not "White keeps an edge", not "this is
 * dubious". Two reasons, and the second is the serious one: the project's rule is that code detects
 * and only the model narrates; and every sentence written here is *seeded into the corpus*, where
 * both composers will quote it as a retrieved source and every validator will certify it as
 * grounded. An invented evaluation here becomes an unfalsifiable citation forever.
 * [LineNarratorTest] pins this with a forbidden-vocabulary list.
 */
object LineNarrator {

    /** `TemplateComposer.sentence` truncates the leading sentence here; longer is quoted mid-clause. */
    const val MAX_SENTENCE_CHARS = 125

    /**
     * A line-specific sentence for [movesSan], or `null` when the moves prove nothing worth saying —
     * in which case the caller keeps [EcoNarrator]'s family claim in first position, which for a
     * base line like `1.e4 c5` is the better sentence anyway.
     */
    fun describe(movesSan: List<String>): String? {
        if (movesSan.size < 3) return null
        val sentence = detect(movesSan) ?: return null
        return sentence.takeIf { it.length <= MAX_SENTENCE_CHARS }
    }

    /**
     * First matching feature wins, ordered by how much it tells a player who is looking at the
     * board: a rule consequence (lost castling) beats a structural choice (fianchetto) beats a
     * timing observation (queen out early), and the fallback simply names the move that defines the
     * line — which is at least *different* for every row, the property the whole change exists for.
     */
    private fun detect(moves: List<String>): String? {
        val defining = moves.last()
        val ply = moves.size
        val moveNumber = (ply + 1) / 2
        val byWhite = ply % 2 == 1
        val side = if (byWhite) "White" else "Black"
        val opponent = if (byWhite) "Black" else "White"

        kingMove(defining)?.let {
            return "$side plays $defining on move $moveNumber, moving the king to ${defining.trimEnd('+', '#').takeLast(2)}."
        }
        fianchettoBishop(moves)?.let { (colour, bishop) ->
            if (colour == side) {
                return "$side fianchettoes with $bishop, placing the bishop on the long diagonal."
            }
        }
        if (defining.startsWith("Q") && moveNumber <= 4) {
            return "$side brings the queen out on move $moveNumber with $defining."
        }
        if (defining.contains('x')) {
            return "$side captures with $defining on move $moveNumber."
        }
        flankPawnPush(defining)?.let {
            return "$side pushes $defining on the wing at move $moveNumber."
        }
        if (defining.startsWith("O-O")) {
            val castle = if (defining == "O-O-O") "queenside" else "kingside"
            return "$side castles $castle on move $moveNumber."
        }
        if (defining.endsWith("+")) {
            return "$side checks with $defining on move $moveNumber, forcing $opponent to respond at once."
        }
        // Fallback: say what the defining move *is*, since that is always true and always specific.
        // Weakest branch by far — it is a description, not an idea — but it is per-line, which is
        // the property the identical-passages failure was about. Improving it means either a board
        // (to know whether a piece is developing or retreating) or a source of chess judgement;
        // both are out of scope for seed-time code. See the plan's R-1 section.
        val square = defining.trimEnd('+', '#')
        PIECE_NAMES[defining.firstOrNull()]?.let { piece ->
            return "$side brings the $piece to ${square.takeLast(2)} at move $moveNumber, the move this line is named for."
        }
        if (square.length == 2 && square[0] in "de") {
            return "$side advances the pawn to $square at move $moveNumber, the move this line is named for."
        }
        return "This line is defined by $defining at move $moveNumber."
    }

    private val PIECE_NAMES = mapOf('N' to "knight", 'B' to "bishop", 'R' to "rook", 'Q' to "queen")

    /** A king move that is not castling: the one rule consequence SAN states outright. */
    private fun kingMove(move: String): String? = move.takeIf { it.startsWith("K") && !it.startsWith("K-") }

    /**
     * A completed fianchetto: the flank pawn moved *and* a bishop later arrived on the square behind
     * it. Requiring both is what keeps this factual — `g3` alone is a pawn move, not a plan.
     */
    private fun fianchettoBishop(moves: List<String>): Pair<String, String>? {
        val whitePlies = moves.filterIndexed { index, _ -> index % 2 == 0 }
        val blackPlies = moves.filterIndexed { index, _ -> index % 2 == 1 }
        FIANCHETTOS.forEach { (pawn, bishop) ->
            val isWhiteSquare = bishop == "Bg2" || bishop == "Bb2"
            val plies = if (isWhiteSquare) whitePlies else blackPlies
            if (pawn in plies && bishop in plies) {
                return (if (isWhiteSquare) "White" else "Black") to bishop
            }
        }
        return null
    }

    /** An a- or h-file pawn advance: a wing move, stated as a wing move and nothing more. */
    private fun flankPawnPush(move: String): String? =
        move.takeIf { it.length == 2 && (it[0] == 'a' || it[0] == 'h') && it[1].isDigit() }

    private val FIANCHETTOS = listOf(
        "g3" to "Bg2",
        "b3" to "Bb2",
        "g6" to "Bg7",
        "b6" to "Bb7",
    )
}
