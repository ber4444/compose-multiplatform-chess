package com.example.coachserver

/**
 * Turns an ECO code into one sentence of actual opening content.
 *
 * The corpus is a lichess ECO index — code, name, move sequence — so a passage built from it alone
 * says only *"X is classified as ECO Y. A representative move sequence is Z."* That is a tautology:
 * even perfect retrieval returns nothing a reader did not already have on the board, and the
 * composers' `sentence()` helper quotes exactly that first sentence. Prefixing a range
 * characterization gives every passage a citable claim about *why* the line is played.
 *
 * Deliberately generic per ECO range rather than per variation: these are textbook descriptions of
 * well-established opening families, not engine evaluations of the position at hand. Nothing here
 * may claim a concrete evaluation, a best move, or an assessment of the user's game.
 */
object EcoNarrator {

    /**
     * One sentence for the family [eco] belongs to, or `null` when the code is unparseable.
     * Kept short on purpose — `TemplateComposer.sentence` truncates the leading sentence at 125
     * characters, so anything longer is quoted mid-clause.
     */
    fun characterize(eco: String): String? {
        val code = eco.trim().uppercase()
        if (code.length < 3) return null
        val volume = code[0]
        val index = code.substring(1, 3).toIntOrNull() ?: return null
        return RANGES.firstOrNull { it.volume == volume && index in it.range }?.text
    }

    private data class EcoRange(val volume: Char, val range: IntRange, val text: String)

    private val RANGES = listOf(
        EcoRange('A', 0..9, "Flank and irregular first moves that delay the central clash and steer play away from mainstream theory."),
        EcoRange('A', 10..39, "English Opening territory: White plays c4 first, fighting for d5 from the flank before committing the centre pawns."),
        EcoRange('A', 40..44, "Queen's-pawn openings where Black skips the main Indian systems and strikes at the centre with an early e5 or c5 push."),
        EcoRange('A', 45..49, "Queen's-pawn games where White delays c4, steering toward slower systems with fewer forced theoretical lines."),
        EcoRange('A', 50..79, "Indian defences and Benoni structures: Black concedes central space, then counterattacks the centre with pawn breaks."),
        EcoRange('A', 80..99, "Dutch Defence: Black meets the queen's pawn with f5, taking kingside space and attacking chances at a cost in light squares."),
        EcoRange('B', 0..9, "Alekhine, Pirc, Modern and other king's-pawn replies where Black invites a broad white centre in order to attack it."),
        EcoRange('B', 10..19, "Caro-Kann Defence: Black supports the d5 push with c6, keeping a sound pawn structure and a healthy light-squared bishop."),
        EcoRange('B', 20..99, "Sicilian Defence: Black answers the king's pawn with c5, trading a flank pawn for a centre pawn to get queenside counterplay."),
        EcoRange('C', 0..19, "French Defence: the e6 and d5 pawns build a solid chain, and Black's counterplay comes from the c5 break."),
        EcoRange('C', 20..29, "Open-game sidelines after the double king's pawn where White develops quickly instead of entering the Italian or Spanish."),
        EcoRange('C', 30..39, "King's Gambit: White offers the f-pawn for rapid development and open lines against Black's king."),
        EcoRange('C', 40..49, "Open games where both sides bring out the knights early, leading to fast-developing, largely symmetrical middlegames."),
        EcoRange('C', 50..59, "Italian Game and Two Knights: the bishops aim at f7, and play revolves around the d4 break and the central tension."),
        EcoRange('C', 60..99, "Ruy Lopez: Bb5 pressures the knight defending e5, and the game turns on the centre and White's queenside pawn advance."),
        EcoRange('D', 0..5, "Queen's-pawn systems where White develops without an early c4, aiming for a solid setup with little forced theory."),
        EcoRange('D', 6..19, "Queen's Gambit and Slav: White offers the c-pawn to deflect Black's d5, and Black chooses how to hold the centre."),
        EcoRange('D', 20..29, "Queen's Gambit Accepted: Black takes on c4 and gives up the centre for quick development and c5 counterplay."),
        EcoRange('D', 30..69, "Queen's Gambit Declined: Black holds d5 with pawns and pieces, accepting less space for a durable structure."),
        EcoRange('D', 70..99, "Grünfeld Defence: Black lets White build a broad pawn centre, then attacks it from the flank with pieces."),
        EcoRange('E', 0..9, "Catalan Opening: White fianchettoes the light-squared bishop and presses the long diagonal against Black's centre."),
        EcoRange('E', 10..19, "Queen's Indian Defence: Black fianchettoes on b7 to fight for e4, keeping the position flexible and solid."),
        EcoRange('E', 20..59, "Nimzo-Indian Defence: the b4 bishop pins the knight and fights for e4, often at the cost of White's doubled c-pawns."),
        EcoRange('E', 60..99, "King's Indian Defence: Black cedes the centre, castles quickly, then strikes with e5 or c5 and a kingside attack."),
    )
}
