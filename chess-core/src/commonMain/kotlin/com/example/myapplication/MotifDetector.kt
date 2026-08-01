package com.example.myapplication

object MotifDetector {

    /**
     * Detects tactical motifs present in the move that transitioned from [stateBefore] to [stateAfter].
     * Focuses on Fork, Pin, Skewer, and Discovered Attack.
     */
    fun detectMotifs(
        stateBefore: GameUiState,
        stateAfter: GameUiState,
        movingSide: Set,
        toSquare: Pair<Int, Int>
    ): List<String> {
        val motifs = mutableListOf<String>()

        val allyPositionsAfter = if (movingSide == Set.WHITE) stateAfter.positionsWhite else stateAfter.positionsBlack
        val allyPiecesAfter = if (movingSide == Set.WHITE) stateAfter.piecesWhite else stateAfter.piecesBlack
        val enemyPositionsAfter = if (movingSide == Set.WHITE) stateAfter.positionsBlack else stateAfter.positionsWhite
        val enemyPiecesAfter = if (movingSide == Set.WHITE) stateAfter.piecesBlack else stateAfter.piecesWhite

        val movedPieceIndex = allyPositionsAfter.indexOf(toSquare)
        if (movedPieceIndex == -1) return emptyList()
        val movedPiece = allyPiecesAfter[movedPieceIndex]

        // 1. Fork: The moved piece attacks 2 or more enemy pieces of higher value than itself, or undefended pieces.
        // For simplicity, we just count if the moved piece attacks 2 or more enemy pieces (excluding pawns unless they are undefended).
        val attackedByMovedPiece = movedPiece.getValidMovesPositions(toSquare, enemyPositionsAfter, allyPositionsAfter)
            .filter { it in enemyPositionsAfter }
        
        // A standard fork: attack 2+ significant pieces (non-pawns)
        val significantAttacks = attackedByMovedPiece.count { pos ->
            val pIdx = enemyPositionsAfter.indexOf(pos)
            pIdx != -1 && enemyPiecesAfter[pIdx] !is Pawn
        }
        if (significantAttacks >= 2) {
            motifs.add("Fork")
        }

        // 2. Discovered Attack: A different friendly long-range piece now attacks an enemy piece
        // that it wasn't attacking before, because the moved piece got out of the way.
        // To check this, we see if any other friendly piece attacks an enemy piece in stateAfter
        // that it could not attack in stateBefore, and the moved piece is NOT the attacker.
        // (This is a simplified check, a true discovered attack also requires the attacker to be long-range).
        var discoveredAttack = false
        val allyPositionsBefore = if (movingSide == Set.WHITE) stateBefore.positionsWhite else stateBefore.positionsBlack
        val allyPiecesBefore = if (movingSide == Set.WHITE) stateBefore.piecesWhite else stateBefore.piecesBlack
        val enemyPositionsBefore = if (movingSide == Set.WHITE) stateBefore.positionsBlack else stateBefore.positionsWhite
        
        for (i in allyPiecesAfter.indices) {
            if (i == movedPieceIndex) continue
            val attacker = allyPiecesAfter[i]
            if (attacker !is Bishop && attacker !is Rook && attacker !is Queen) continue
            
            val attacksAfter = attacker.getValidMovesPositions(allyPositionsAfter[i], enemyPositionsAfter, allyPositionsAfter)
            val attackedEnemiesAfter = attacksAfter.filter { it in enemyPositionsAfter }
            
            if (attackedEnemiesAfter.isNotEmpty()) {
                // Find this piece in stateBefore
                val beforeIdx = allyPositionsBefore.indexOf(allyPositionsAfter[i])
                if (beforeIdx != -1) {
                    val attackerBefore = allyPiecesBefore[beforeIdx]
                    val attacksBefore = attackerBefore.getValidMovesPositions(allyPositionsBefore[beforeIdx], enemyPositionsBefore, allyPositionsBefore)
                    val newAttacks = attackedEnemiesAfter.filter { it !in attacksBefore }
                    if (newAttacks.isNotEmpty()) {
                        discoveredAttack = true
                        break
                    }
                }
            }
        }
        if (discoveredAttack) motifs.add("Discovered Attack")

        // 3. Pin & 4. Skewer
        // A true implementation involves casting rays and seeing if exactly one enemy piece blocks
        // an attack on a more valuable piece. We'll do a simplified raycast.
        var pinFound = false
        var skewerFound = false

        for (i in allyPiecesAfter.indices) {
            val attacker = allyPiecesAfter[i]
            val attackerPos = allyPositionsAfter[i]
            if (attacker !is Bishop && attacker !is Rook && attacker !is Queen) continue

            val directions = getRayDirections(attacker)
            for (dir in directions) {
                val ray = castRay(attackerPos, dir, enemyPositionsAfter, allyPositionsAfter)
                // A ray contains pieces hit in order.
                // We care about rays that hit exactly 2 enemy pieces and 0 ally pieces.
                val enemyHits = ray.filter { it in enemyPositionsAfter }
                val allyHits = ray.filter { it in allyPositionsAfter }
                
                if (enemyHits.size >= 2 && allyHits.isEmpty()) {
                    val firstEnemyIdx = enemyPositionsAfter.indexOf(enemyHits[0])
                    val secondEnemyIdx = enemyPositionsAfter.indexOf(enemyHits[1])
                    if (firstEnemyIdx != -1 && secondEnemyIdx != -1) {
                        val firstEnemy = enemyPiecesAfter[firstEnemyIdx]
                        val secondEnemy = enemyPiecesAfter[secondEnemyIdx]
                        
                        val val1 = pieceValue(firstEnemy)
                        val val2 = pieceValue(secondEnemy)
                        
                        if (val1 < val2) {
                            pinFound = true
                        } else if (val1 > val2) {
                            skewerFound = true
                        }
                    }
                }
            }
        }
        if (pinFound) motifs.add("Pin")
        if (skewerFound) motifs.add("Skewer")

        return motifs
    }

    private fun getRayDirections(piece: Piece): List<Pair<Int, Int>> {
        val orth = listOf(Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1))
        val diag = listOf(Pair(1, 1), Pair(1, -1), Pair(-1, 1), Pair(-1, -1))
        return when (piece) {
            is Rook -> orth
            is Bishop -> diag
            is Queen -> orth + diag
            else -> emptyList()
        }
    }

    private fun castRay(
        start: Pair<Int, Int>,
        dir: Pair<Int, Int>,
        enemyPositions: List<Pair<Int, Int>>,
        allyPositions: List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> {
        val hits = mutableListOf<Pair<Int, Int>>()
        var r = start.first + dir.first
        var c = start.second + dir.second
        while (r in 0 until BOARD_SIZE && c in 0 until BOARD_SIZE) {
            val p = Pair(r, c)
            if (p in allyPositions) {
                hits.add(p)
                break // Ray blocked by ally
            }
            if (p in enemyPositions) {
                hits.add(p)
                if (hits.size == 2) break // Found two enemies in a line
            }
            r += dir.first
            c += dir.second
        }
        return hits
    }

    private fun pieceValue(piece: Piece): Int = when (piece) {
        is Queen -> 900
        is Rook -> 500
        is Bishop -> 330
        is Knight -> 320
        is Pawn -> 100
        is King -> 10000 // Invaluable
        else -> 0
    }
}
