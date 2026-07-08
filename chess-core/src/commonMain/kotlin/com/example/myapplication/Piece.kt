package com.example.myapplication

const val BOARD_SIZE = 8

interface Piece {
    val set: Set
    val name: String

    fun getValidMovesPositions(
        position: Pair<Int, Int>,
        enemyPositions: List<Pair<Int, Int>>,
        allyPositions: List<Pair<Int, Int>>
    ): List<Pair<Int, Int>>
}

private fun Piece.validateUnboundMove(
    direction: Pair<Int, Int>,
    position: Pair<Int, Int>,
    allyPositions: List<Pair<Int, Int>>,
    enemyPositions: List<Pair<Int, Int>>
): List<Pair<Int, Int>> {
    val moves = mutableListOf<Pair<Int, Int>>()
    var x = position.first + direction.first
    var y = position.second + direction.second

    while (x in 0 until BOARD_SIZE && y in 0 until BOARD_SIZE) {
        val pos = Pair(x, y)

        if (allyPositions.contains(pos)) {
            return moves
        } else {
            moves.add(pos)
            if (enemyPositions.contains(pos)) {
                return moves
            }
        }
        x += direction.first
        y += direction.second
    }

    return moves
}

private fun Piece.validateBoundMove(
    direction: Pair<Int, Int>,
    position: Pair<Int, Int>,
    allyPositions: List<Pair<Int, Int>>
): List<Pair<Int, Int>> {
    val moves = mutableListOf<Pair<Int, Int>>()
    val x = position.first + direction.first
    val y = position.second + direction.second

    if (x in 0 until BOARD_SIZE && y in 0 until BOARD_SIZE && !allyPositions.contains(Pair(x, y))) {
        moves.add(Pair(x, y))
    }

    return moves
}

enum class Set {
    WHITE,
    BLACK
}

class King(override val set: Set) : Piece {
    override val name = "King"

    override fun getValidMovesPositions(
        position: Pair<Int, Int>,
        enemyPositions: List<Pair<Int, Int>>,
        allyPositions: List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> {
        val moves = mutableListOf<Pair<Int, Int>>()
        val directions = listOf(
            Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1),
            Pair(1, 1), Pair(1, -1), Pair(-1, 1), Pair(-1, -1)
        )
        for (direction in directions) {
            moves += validateBoundMove(direction, position, allyPositions)
        }
        return moves
    }
}

class Bishop(override val set: Set) : Piece {
    override val name = "Bishop"

    override fun getValidMovesPositions(
        position: Pair<Int, Int>,
        enemyPositions: List<Pair<Int, Int>>,
        allyPositions: List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> {
        val moves = mutableListOf<Pair<Int, Int>>()
        val directions = listOf(Pair(1, 1), Pair(1, -1), Pair(-1, 1), Pair(-1, -1))
        for ((dx, dy) in directions) {
            moves += validateUnboundMove(Pair(dx, dy), position, allyPositions, enemyPositions)
        }
        return moves
    }
}

class Knight(override val set: Set) : Piece {
    override val name = "Knight"

    override fun getValidMovesPositions(
        position: Pair<Int, Int>,
        enemyPositions: List<Pair<Int, Int>>,
        allyPositions: List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> {
        val moves = mutableListOf<Pair<Int, Int>>()
        val deltas = listOf(
            Pair(2, 1), Pair(1, 2), Pair(-1, 2), Pair(-2, 1),
            Pair(-2, -1), Pair(-1, -2), Pair(1, -2), Pair(2, -1)
        )
        for (delta in deltas) {
            moves += validateBoundMove(delta, position, allyPositions)
        }
        return moves
    }
}

class Pawn(override val set: Set) : Piece {
    override val name = "Pawn"

    override fun getValidMovesPositions(
        position: Pair<Int, Int>,
        enemyPositions: List<Pair<Int, Int>>,
        allyPositions: List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> {
        val moves = mutableListOf<Pair<Int, Int>>()
        val dir = if (set == Set.BLACK) 1 else -1
        val startRow = if (set == Set.BLACK) 1 else 6

        val forward = Pair(position.first + dir, position.second)
        if (forward.first in 0 until BOARD_SIZE && forward !in allyPositions && forward !in enemyPositions) {
            moves.add(forward)
            if (position.first == startRow) {
                val doubleForward = Pair(position.first + 2 * dir, position.second)
                if (doubleForward.first in 0 until BOARD_SIZE && doubleForward !in allyPositions && doubleForward !in enemyPositions) {
                    moves.add(doubleForward)
                }
            }
        }
        for (dc in listOf(-1, 1)) {
            val capture = Pair(position.first + dir, position.second + dc)
            if (capture.first in 0 until BOARD_SIZE && capture.second in 0 until BOARD_SIZE && capture in enemyPositions) {
                moves.add(capture)
            }
        }
        return moves
    }
}

class Queen(override val set: Set) : Piece {
    override val name = "Queen"

    override fun getValidMovesPositions(
        position: Pair<Int, Int>,
        enemyPositions: List<Pair<Int, Int>>,
        allyPositions: List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> {
        val moves = mutableListOf<Pair<Int, Int>>()
        val directions = listOf(
            Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1),
            Pair(1, 1), Pair(-1, -1), Pair(1, -1), Pair(-1, 1)
        )

        for ((dx, dy) in directions) {
            moves += validateUnboundMove(Pair(dx, dy), position, allyPositions, enemyPositions)
        }
        return moves
    }
}

class Rook(override val set: Set) : Piece {
    override val name = "Rook"

    override fun getValidMovesPositions(
        position: Pair<Int, Int>,
        enemyPositions: List<Pair<Int, Int>>,
        allyPositions: List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> {
        val moves = mutableListOf<Pair<Int, Int>>()
        val directions = listOf(Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1))
        for ((dx, dy) in directions) {
            var x = position.first + dx
            var y = position.second + dy
            while (x in 0 until BOARD_SIZE && y in 0 until BOARD_SIZE) {
                val pos = Pair(x, y)
                if (!allyPositions.contains(pos)) {
                    moves.add(pos)
                    if (enemyPositions.contains(pos)) break
                } else {
                    break
                }
                x += dx
                y += dy
            }
        }
        return moves
    }
}
