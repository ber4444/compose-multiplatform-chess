package com.example.myapplication

/** Squares per rank and per file. A square is `(row, column)`; row 0 is Black's back rank. */
const val BOARD_SIZE = 8

/**
 * A chess piece of one colour. [getValidMovesPositions] answers where it could go if check did
 * not exist — legality against check, castling and en passant are layered on in `Move.kt`.
 */
interface Piece {
    val set: Set
    val name: String

    /** Pseudo-legal destinations from [position], given who stands where. */
    fun getValidMovesPositions(
        position: Pair<Int, Int>,
        enemyPositions: List<Pair<Int, Int>>,
        allyPositions: List<Pair<Int, Int>>
    ): List<Pair<Int, Int>>
}

/** The two sides. Named `Set` (as in "a chess set's colour") for historical reasons. */
enum class Set { WHITE, BLACK }

private fun Pair<Int, Int>.onBoard(): Boolean = first in 0 until BOARD_SIZE && second in 0 until BOARD_SIZE

private operator fun Pair<Int, Int>.plus(offset: Pair<Int, Int>): Pair<Int, Int> =
    Pair(first + offset.first, second + offset.second)

// Output order follows these lists, so keep them stable: callers index into move lists.
private val ORTHOGONAL = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
private val DIAGONAL = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
private val QUEEN_RAYS = ORTHOGONAL + listOf(1 to 1, -1 to -1, 1 to -1, -1 to 1)
private val KING_STEPS = ORTHOGONAL + DIAGONAL
private val KNIGHT_JUMPS = listOf(2 to 1, 1 to 2, -1 to 2, -2 to 1, -2 to -1, -1 to -2, 1 to -2, 2 to -1)

/**
 * Rider movement: walk each ray until the board edge, stopping short of a friendly piece and
 * stopping *on* an enemy one (the capture).
 */
private fun slide(
    from: Pair<Int, Int>,
    rays: List<Pair<Int, Int>>,
    own: List<Pair<Int, Int>>,
    enemy: List<Pair<Int, Int>>,
): List<Pair<Int, Int>> = buildList {
    for (ray in rays) {
        var square = from + ray
        while (square.onBoard() && square !in own) {
            add(square)
            if (square in enemy) break
            square += ray
        }
    }
}

/** Leaper movement: each offset is one candidate, kept if it is on the board and not our own. */
private fun leap(
    from: Pair<Int, Int>,
    offsets: List<Pair<Int, Int>>,
    own: List<Pair<Int, Int>>,
): List<Pair<Int, Int>> = offsets.map { from + it }.filter { it.onBoard() && it !in own }

class King(override val set: Set) : Piece {
    override val name = "King"

    override fun getValidMovesPositions(
        position: Pair<Int, Int>, enemyPositions: List<Pair<Int, Int>>, allyPositions: List<Pair<Int, Int>>,
    ): List<Pair<Int, Int>> = leap(position, KING_STEPS, allyPositions)
}

class Bishop(override val set: Set) : Piece {
    override val name = "Bishop"

    override fun getValidMovesPositions(
        position: Pair<Int, Int>, enemyPositions: List<Pair<Int, Int>>, allyPositions: List<Pair<Int, Int>>,
    ): List<Pair<Int, Int>> = slide(position, DIAGONAL, allyPositions, enemyPositions)
}

class Knight(override val set: Set) : Piece {
    override val name = "Knight"

    override fun getValidMovesPositions(
        position: Pair<Int, Int>, enemyPositions: List<Pair<Int, Int>>, allyPositions: List<Pair<Int, Int>>,
    ): List<Pair<Int, Int>> = leap(position, KNIGHT_JUMPS, allyPositions)
}

class Pawn(override val set: Set) : Piece {
    override val name = "Pawn"

    override fun getValidMovesPositions(
        position: Pair<Int, Int>, enemyPositions: List<Pair<Int, Int>>, allyPositions: List<Pair<Int, Int>>,
    ): List<Pair<Int, Int>> {
        // White advances towards row 0, Black towards row 7.
        val forward = if (set == Set.WHITE) -1 else 1
        val homeRow = if (set == Set.WHITE) BOARD_SIZE - 2 else 1
        fun vacant(square: Pair<Int, Int>) = square.onBoard() && square !in allyPositions && square !in enemyPositions

        val pushes = buildList {
            val single = position + (forward to 0)
            if (vacant(single)) {
                add(single)
                val double = single + (forward to 0)
                if (position.first == homeRow && vacant(double)) add(double)
            }
        }
        // En passant is not here: its target square is empty, so it is generated in Move.kt.
        val captures = listOf(-1, 1)
            .map { position + (forward to it) }
            .filter { it.onBoard() && it in enemyPositions }
        return pushes + captures
    }
}

class Queen(override val set: Set) : Piece {
    override val name = "Queen"

    override fun getValidMovesPositions(
        position: Pair<Int, Int>, enemyPositions: List<Pair<Int, Int>>, allyPositions: List<Pair<Int, Int>>,
    ): List<Pair<Int, Int>> = slide(position, QUEEN_RAYS, allyPositions, enemyPositions)
}

class Rook(override val set: Set) : Piece {
    override val name = "Rook"

    override fun getValidMovesPositions(
        position: Pair<Int, Int>, enemyPositions: List<Pair<Int, Int>>, allyPositions: List<Pair<Int, Int>>,
    ): List<Pair<Int, Int>> = slide(position, ORTHOGONAL, allyPositions, enemyPositions)
}
