package com.example.myapplication.movecoach

import com.example.myapplication.BOARD_SIZE
import com.example.myapplication.Bishop
import com.example.myapplication.GameUiState
import com.example.myapplication.King
import com.example.myapplication.Knight
import com.example.myapplication.Pawn
import com.example.myapplication.Piece
import com.example.myapplication.Queen
import com.example.myapplication.Rook
import com.example.myapplication.Set
import com.example.myapplication.UciMoveConverter
import com.example.myapplication.getAllLegalMoves

/**
 * Deterministic strategic read on one square, for Explain mode's tap-a-square query.
 *
 * Same contract as [DeterministicCoach]: code detects, the model only narrates. The text this
 * produces is both the free-tier/fallback answer *and* the thing the model is asked to rewrite, so
 * whatever it fails to notice is invisible downstream — an explanation of "Empty square on f3" can
 * only ever be rewritten into a fluent way of saying nothing.
 */
object SquareInsight {

    private const val MAX_CHARS = 300

    private val CENTER = setOf(3 to 3, 3 to 4, 4 to 3, 4 to 4)

    /**
     * Control is computed from *attack* squares, not from [Piece.getValidMovesPositions].
     *
     * The two differ exactly where this feature needs them to: a pawn covers the two squares
     * diagonally ahead whether or not an enemy stands there, and any piece guarding one of its own
     * pieces never appears in move generation at all. Asking the move generator who controls a
     * square reports an empty square as free precisely when a pawn is covering it — the case a
     * player most needs to be warned about.
     */
    private fun attacksFrom(
        piece: Piece,
        from: Pair<Int, Int>,
        occupied: kotlin.collections.Set<Pair<Int, Int>>,
    ): List<Pair<Int, Int>> = when (piece) {
        is Pawn -> {
            val dir = if (piece.set == Set.BLACK) 1 else -1
            listOf(from.first + dir to from.second - 1, from.first + dir to from.second + 1)
                .filter { it.onBoard() }
        }
        is Knight -> listOf(
            2 to 1, 1 to 2, -1 to 2, -2 to 1, -2 to -1, -1 to -2, 1 to -2, 2 to -1,
        ).map { (dr, dc) -> from.first + dr to from.second + dc }.filter { it.onBoard() }
        is King -> ALL_DIRECTIONS
            .map { (dr, dc) -> from.first + dr to from.second + dc }
            .filter { it.onBoard() }
        is Bishop -> rays(from, DIAGONALS, occupied)
        is Rook -> rays(from, ORTHOGONALS, occupied)
        is Queen -> rays(from, ALL_DIRECTIONS, occupied)
        else -> emptyList()
    }

    private fun rays(
        from: Pair<Int, Int>,
        directions: List<Pair<Int, Int>>,
        occupied: kotlin.collections.Set<Pair<Int, Int>>,
    ): List<Pair<Int, Int>> {
        val squares = mutableListOf<Pair<Int, Int>>()
        for ((dr, dc) in directions) {
            var square = from.first + dr to from.second + dc
            while (square.onBoard()) {
                squares += square
                // The blocker itself is attacked (defended, if it is a friend); anything behind it
                // is not.
                if (square in occupied) break
                square = square.first + dr to square.second + dc
            }
        }
        return squares
    }

    private fun Pair<Int, Int>.onBoard() = first in 0 until BOARD_SIZE && second in 0 until BOARD_SIZE

    private val ORTHOGONALS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private val DIAGONALS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
    private val ALL_DIRECTIONS = ORTHOGONALS + DIAGONALS

    private fun attackersOf(
        square: Pair<Int, Int>,
        pieces: List<Piece>,
        positions: List<Pair<Int, Int>>,
        occupied: kotlin.collections.Set<Pair<Int, Int>>,
    ): List<Piece> = pieces.filterIndexed { index, piece ->
        square in attacksFrom(piece, positions[index], occupied)
    }

    /**
     * How many pieces of each side attack [square], as (defenders, attackers) from [side]'s view.
     *
     * Exposed for `MotifDetector`, which needs the same question B16 already answers for Explain
     * mode — "is this square contested, and by whom?" — to decide `hangs-piece`, `defends` and
     * `threatens`. Sharing it keeps one ray implementation: a second copy would drift, and the two
     * would then disagree about whether a piece is hanging depending on which surface asked.
     */
    internal fun contest(
        state: GameUiState,
        square: Pair<Int, Int>,
        side: Set,
    ): Pair<Int, Int> {
        val white = side == Set.WHITE
        val ownPieces = if (white) state.piecesWhite else state.piecesBlack
        val ownPositions = if (white) state.positionsWhite else state.positionsBlack
        val foePieces = if (white) state.piecesBlack else state.piecesWhite
        val foePositions = if (white) state.positionsBlack else state.positionsWhite
        val occupied = (ownPositions + foePositions).toSet()
        // A piece does not defend the square it stands on, so exclude it from its own defender count.
        val defenders = ownPieces.filterIndexed { i, piece ->
            ownPositions[i] != square && square in attacksFrom(piece, ownPositions[i], occupied)
        }
        val attackers = attackersOf(square, foePieces, foePositions, occupied)
        return defenders.size to attackers.size
    }

    /** Squares [piece] at [from] attacks in [state]. Shared for the same reason as [contest]. */
    internal fun attacked(
        state: GameUiState,
        piece: Piece,
        from: Pair<Int, Int>,
    ): List<Pair<Int, Int>> {
        val occupied = (state.positionsWhite + state.positionsBlack).toSet()
        return attacksFrom(piece, from, occupied)
    }

    /** Ordering only — used to name the cheapest attacker, never to score a position. */
    private fun Piece.exchangeRank(): Int = when (this) {
        is Pawn -> 0
        is Knight, is Bishop -> 1
        is Rook -> 2
        is Queen -> 3
        else -> 4
    }

    fun buildHeadline(state: GameUiState, square: Pair<Int, Int>, viewer: Set): String {
        val name = UciMoveConverter.positionToUciSquare(square)
        val read = read(state, square, viewer)
        val verdict = when {
            read.foeAttackers == 0 && read.ownAttackers == 0 -> "no man's land"
            read.foeAttackers == 0 -> "yours to take"
            read.ownAttackers > read.foeAttackers -> "you hold it"
            read.ownAttackers == read.foeAttackers -> "contested"
            else -> "${read.foeName} holds it"
        }
        return "$name — $verdict"
    }

    fun buildExplanation(state: GameUiState, square: Pair<Int, Int>, viewer: Set): String {
        val name = UciMoveConverter.positionToUciSquare(square)
        val read = read(state, square, viewer)

        val occupancy = when {
            read.ownPiece != null -> "Your ${read.ownPiece.name.lowercase()} stands on $name."
            read.foePiece != null -> "${read.foeName}'s ${read.foePiece.name.lowercase()} stands on $name."
            square in CENTER -> "$name is an empty central square."
            else -> "$name is empty."
        }

        val control = when {
            read.foeAttackers == 0 && read.ownAttackers == 0 -> "Neither side covers it."
            read.foeAttackers == 0 -> "You cover it ${times(read.ownAttackers)} and ${read.foeName} not at all."
            read.ownAttackers == 0 -> "${read.foeName} covers it ${times(read.foeAttackers)} and you not at all."
            else -> "You cover it ${times(read.ownAttackers)}, ${read.foeName} ${times(read.foeAttackers)}."
        }

        val implication = when {
            read.foeAttackers == 0 ->
                if (read.movers.isEmpty()) "Nothing of yours reaches it yet, but it is free ground."
                else "Your ${list(read.movers)} can step in unopposed."
            read.cheapestFoe is Pawn ->
                "A ${read.foeName.lowercase()} pawn guards it, so only a pawn belongs there."
            read.ownAttackers >= read.foeAttackers ->
                if (read.movers.isEmpty()) "You have the defenders to hold it once something can get there."
                else "You back it up enough to park your ${list(read.movers)} there."
            else ->
                "${read.foeName} has more attackers than you have defenders, so a piece there would drop."
        }

        return "$occupancy $control $implication".clamp()
    }

    private fun times(n: Int) = when (n) {
        1 -> "once"
        2 -> "twice"
        else -> "$n times"
    }

    private fun list(names: List<String>) = when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} or ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " or " + names.last()
    }

    private fun String.clamp() =
        if (length <= MAX_CHARS) this else take(MAX_CHARS - 1).trimEnd() + "…"

    private class Read(
        val ownPiece: Piece?,
        val foePiece: Piece?,
        val ownAttackers: Int,
        val foeAttackers: Int,
        val cheapestFoe: Piece?,
        val movers: List<String>,
        val foeName: String,
    )

    private fun read(state: GameUiState, square: Pair<Int, Int>, viewer: Set): Read {
        val white = viewer == Set.WHITE
        val ownPieces = if (white) state.piecesWhite else state.piecesBlack
        val ownPositions = if (white) state.positionsWhite else state.positionsBlack
        val foePieces = if (white) state.piecesBlack else state.piecesWhite
        val foePositions = if (white) state.positionsBlack else state.positionsWhite
        val occupied = (ownPositions + foePositions).toSet()

        val ownIndex = ownPositions.indexOf(square)
        val foeIndex = foePositions.indexOf(square)

        val own = attackersOf(square, ownPieces, ownPositions, occupied)
        val foe = attackersOf(square, foePieces, foePositions, occupied)

        val movers = getAllLegalMoves(
            enemyPositions = foePositions,
            enemyPieces = foePieces,
            allyPositions = ownPositions,
            allyPieces = ownPieces,
            castlingRights = state.castlingRights,
            enPassantTarget = state.enPassantTarget,
        ).filter { it.first == square }
            .map { ownPieces[it.second].name.lowercase() }
            .distinct()

        return Read(
            ownPiece = ownIndex.takeIf { it != -1 }?.let { ownPieces[it] },
            foePiece = foeIndex.takeIf { it != -1 }?.let { foePieces[it] },
            ownAttackers = own.size,
            foeAttackers = foe.size,
            cheapestFoe = foe.minByOrNull { it.exchangeRank() },
            movers = movers,
            foeName = if (white) "Black" else "White",
        )
    }
}
