package com.example.myapplication

import kotlinx.serialization.Serializable

/**
 * PGN tag-pair model. Only the Seven Tag Roster (Event/Site/Date/Round/White/Black/Result) plus
 * a few optional tags the app cares about (TimeControl, SetUp/FEN) — anything else is ignored.
 */
@Serializable
data class PgnTags(
    val event: String = "Casual Game",
    val site: String = "Compose Multiplatform Chess",
    val date: String,        // "YYYY.MM.DD" — caller supplies via todayPgnDate()
    val round: String = "-",
    val white: String = "Player",
    val black: String = "Stockfish",
    val result: String,      // "1-0" | "0-1" | "1/2-1/2" | "*"
    val timeControl: String? = null,
    val setUpFen: String? = null,
)

/**
 * Serializes a game ([PgnTags] + [moves]) to a PGN string. Movetext follows the standard
 * `1. e4 e5 2. Nf3 Nc6 ... <result>` shape, ending with the result token.
 *
 * Implementation notes:
 *  - When [PgnTags.setUpFen] is non-null, emits the `SetUp`/`FEN` tags and numbers movetext from
 *    the starting fullmove number parsed out of the FEN (defaults to 1 on parse failure).
 *  - The output is one logical line of movetext (no 80-col wrapping) — valid PGN, easy to verify.
 */
object PgnSerializer {

    fun resultToken(winState: WinState, playerIsWhite: Boolean = true): String = when (winState) {
        WinState.WHITE -> if (playerIsWhite) "1-0" else "0-1"
        WinState.BLACK -> if (playerIsWhite) "0-1" else "1-0"
        WinState.DRAW, WinState.STALEMATE -> "1/2-1/2"
        WinState.NONE -> "*"
    }

    fun toPgn(tags: PgnTags, moves: List<MoveRecord>): String {
        val sb = StringBuilder()

        // --- Tag pairs ---
        sb.append("[Event \"").append(tags.event).append("\"]\n")
        sb.append("[Site \"").append(tags.site).append("\"]\n")
        sb.append("[Date \"").append(tags.date).append("\"]\n")
        sb.append("[Round \"").append(tags.round).append("\"]\n")
        sb.append("[White \"").append(tags.white).append("\"]\n")
        sb.append("[Black \"").append(tags.black).append("\"]\n")
        sb.append("[Result \"").append(tags.result).append("\"]\n")
        tags.timeControl?.let { sb.append("[TimeControl \"").append(it).append("\"]\n") }
        tags.setUpFen?.let {
            sb.append("[SetUp \"1\"]\n")
            sb.append("[FEN \"").append(it).append("\"]\n")
        }

        // --- Movetext ---
        // Starting move number: 1 for the standard start, else parsed from the SetUp FEN's
        // fullmove field (position 5 if all six fields are present, lower-indexed if elided).
        val fenParts = tags.setUpFen?.split(" ") ?: emptyList()
        var moveNumber = (fenParts.getOrNull(5) ?: fenParts.lastOrNull())
            ?.let { runCatching { it.toInt() }.getOrNull() } ?: 1
        var ply = 0  // 0 = next move is White's, 1 = next is Black's (within the current moveNumber)

        // If the SetUp FEN says it's Black's turn, the first White-ply is skipped and we start with
        // a "N..." prefix on the first move.
        var prefixBlackToMove = fenParts.getOrNull(1)?.let { it == "b" } ?: false

        val movetext = StringBuilder()
        for ((i, record) in moves.withIndex()) {
            if (ply == 0) {
                if (movetext.isNotEmpty()) movetext.append(' ')
                if (prefixBlackToMove && i == 0) {
                    movetext.append(moveNumber).append(". ... ").append(record.san)
                    prefixBlackToMove = false
                    ply = 1
                    continue
                }
                movetext.append(moveNumber).append(". ").append(record.san)
            } else {
                movetext.append(' ').append(record.san)
                moveNumber += 1
            }
            ply = 1 - ply
        }

        // Trailing result token (PGN requires it even for "*" — an in-progress game).
        if (movetext.isNotEmpty()) movetext.append(' ')
        movetext.append(tags.result)

        sb.append(movetext)
        return sb.toString()
    }
}
