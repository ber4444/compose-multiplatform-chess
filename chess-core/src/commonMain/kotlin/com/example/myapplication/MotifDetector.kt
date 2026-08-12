package com.example.myapplication

import com.example.myapplication.movecoach.SquareInsight

object MotifDetector {

    /**
     * The motif vocabulary. **These constants are the contract with
     * [com.example.myapplication.movecoach.DeterministicCoach]**, which matches motif strings to
     * render headlines and explanations.
     *
     * They were briefly capitalised ("Fork", "Discovered Attack") while the coach matched
     * lowercase-hyphenated names, so the intersection was empty and every motif branch in the coach
     * was unreachable — detected, stored, and silently discarded. `motif vocabulary is understood by
     * DeterministicCoach` in `MotifDetectorTest` now pins the two together; add a constant here and
     * that test fails until the coach handles it.
     */
    const val FORK = "fork"
    const val PIN = "pin"
    const val SKEWER = "skewer"
    const val DISCOVERED_ATTACK = "discovered-attack"

    // Everything below was written into DeterministicCoach's `when` from the start and emitted by
    // nothing, so eleven of its fifteen branches and seven of its eleven headlines were dead code.
    // Unless a move happened to be one of the four tactical patterns above — rare — every branch
    // missed and the line fell through to "The position stays roughly balanced.", which is exactly
    // how the coach read on ordinary moves. `motif vocabulary is understood by DeterministicCoach`
    // pinned detector -> coach; nothing pinned coach -> detector, which is how they rotted.
    const val CHECKMATE = "checkmate"
    const val CHECK = "check"
    const val PROMOTION = "promotion"
    const val CASTLE_KINGSIDE = "castle-kingside"
    const val CASTLE_QUEENSIDE = "castle-queenside"
    const val CAPTURE = "capture"
    const val RECAPTURE = "recapture"
    const val MATERIAL_SWING = "material-swing"
    const val HANGS_PIECE = "hangs-piece"
    const val DEFENDS = "defends"
    const val THREATENS = "threatens"
    const val DEVELOPS = "develops"
    const val CENTER_CONTROL = "center-control"
    const val KING_SAFETY = "king-safety"
    const val PAWN_PUSH = "pawn-push"

    /**
     * Every motif this detector can emit, **most newsworthy first**.
     *
     * This is the order [detectMotifs] returns them in, and therefore the priority
     * `DeterministicCoach.buildHeadline` gets when it takes the first motif it has a phrase for. A
     * move that both forks and gives check should headline as the fork; a move that mates should
     * headline as mate whatever else it does.
     */
    val ALL_MOTIFS = listOf(
        CHECKMATE, PROMOTION,
        FORK, PIN, SKEWER, DISCOVERED_ATTACK,
        HANGS_PIECE, CHECK,
        RECAPTURE, MATERIAL_SWING, CAPTURE,
        CASTLE_KINGSIDE, CASTLE_QUEENSIDE, DEVELOPS, KING_SAFETY, PAWN_PUSH,
        CENTER_CONTROL, DEFENDS, THREATENS,
    )

    /** d4, e4, d5, e5 — row 0 is rank 8, so the four central squares are rows 3-4 x cols 3-4. */
    private val CENTER_SQUARES = listOf(3 to 3, 3 to 4, 4 to 3, 4 to 4)

    /**
     * Detects tactical motifs present in the move that transitioned from [stateBefore] to [stateAfter].
     * Focuses on Fork, Pin, Skewer, and Discovered Attack.
     */
    fun detectMotifs(
        stateBefore: GameUiState,
        stateAfter: GameUiState,
        movingSide: Set,
        toSquare: Pair<Int, Int>,
        /**
         * The square the moved piece came from. Optional so the four original tactical detections
         * keep working without it, but every positional motif needs it — a move is not "developing"
         * or "castling" without knowing where it started. Callers hold the UCI, so it is
         * `parseUciMove(uci).first`.
         */
        fromSquare: Pair<Int, Int>? = null,
        /** True when the move promoted a pawn; from the UCI's optional 5th character. */
        promoted: Boolean = false,
        /** Destination of the previous ply, so a capture there reads as a recapture. */
        previousToSquare: Pair<Int, Int>? = null,
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
            motifs.add(FORK)
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
        if (discoveredAttack) motifs.add(DISCOVERED_ATTACK)

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
                        
                        if (isPin(firstEnemy, secondEnemy)) {
                            pinFound = true
                        } else if (isSkewer(firstEnemy, secondEnemy)) {
                            skewerFound = true
                        }
                    }
                }
            }
        }
        if (pinFound) motifs.add(PIN)
        if (skewerFound) motifs.add(SKEWER)

        motifs += positionalMotifs(
            stateBefore = stateBefore,
            stateAfter = stateAfter,
            movingSide = movingSide,
            toSquare = toSquare,
            fromSquare = fromSquare,
            promoted = promoted,
            previousToSquare = previousToSquare,
            movedPiece = movedPiece,
            tactical = motifs.toList(),
        )

        // Returned in declared priority order rather than detection order, so the headline is chosen
        // by how newsworthy a motif is and not by where its detection happens to sit in this file.
        return motifs.distinct().sortedBy { ALL_MOTIFS.indexOf(it) }
    }

    /**
     * [detectMotifs], plus a sentence per motif naming the pieces and squares actually involved.
     *
     * The generic templates in `DeterministicCoach` are *definitions*: "It pins an enemy piece
     * against a more valuable one." says what a pin is, not what happened on this board, and reads
     * like a glossary. The information to do better was already being computed — the pin detector
     * finds the attacker, the pinned piece and the piece behind it in order to classify the move —
     * and then thrown away.
     *
     * Details are best-effort and sparse. A motif with nothing specific to add simply has no entry
     * and the coach keeps its definition.
     */
    fun detectDetailed(
        stateBefore: GameUiState,
        stateAfter: GameUiState,
        movingSide: Set,
        toSquare: Pair<Int, Int>,
        fromSquare: Pair<Int, Int>? = null,
        promoted: Boolean = false,
        previousToSquare: Pair<Int, Int>? = null,
    ): Detected {
        val motifs = detectMotifs(
            stateBefore, stateAfter, movingSide, toSquare, fromSquare, promoted, previousToSquare,
        )
        return Detected(motifs, describe(motifs, stateBefore, stateAfter, movingSide, toSquare, fromSquare))
    }

    data class Detected(val motifs: List<String>, val details: Map<String, String>)

    private fun describe(
        motifs: List<String>,
        stateBefore: GameUiState,
        stateAfter: GameUiState,
        movingSide: Set,
        toSquare: Pair<Int, Int>,
        fromSquare: Pair<Int, Int>?,
    ): Map<String, String> {
        val white = movingSide == Set.WHITE
        val allyPieces = if (white) stateAfter.piecesWhite else stateAfter.piecesBlack
        val allyPositions = if (white) stateAfter.positionsWhite else stateAfter.positionsBlack
        val enemyPieces = if (white) stateAfter.piecesBlack else stateAfter.piecesWhite
        val enemyPositions = if (white) stateAfter.positionsBlack else stateAfter.positionsWhite

        val movedIndex = allyPositions.indexOf(toSquare)
        val moved = allyPieces.getOrNull(movedIndex) ?: return emptyMap()
        val movedName = moved.name.lowercase()
        val at = UciMoveConverter.positionToUciSquare(toSquare)
        val details = mutableMapOf<String, String>()

        val attacked = SquareInsight.attacked(stateAfter, moved, toSquare)
        val hitEnemies = attacked.filter { it in enemyPositions }
            .map { square ->
                val piece = enemyPieces[enemyPositions.indexOf(square)]
                "${piece.name.lowercase()} on ${UciMoveConverter.positionToUciSquare(square)}"
            }

        if (FORK in motifs && hitEnemies.size >= 2) {
            details[FORK] = "Your $movedName on $at hits the ${hitEnemies[0]} and the ${hitEnemies[1]} at once."
        }
        if (THREATENS in motifs) {
            winnableTargets(stateAfter, attacked, movingSide, moved).firstOrNull()?.let { square ->
                val target = enemyPieces[enemyPositions.indexOf(square)]
                details[THREATENS] = "Your $movedName on $at wins the ${target.name.lowercase()} " +
                    "on ${UciMoveConverter.positionToUciSquare(square)} if it stays there."
            }
        }
        for (motif in listOf(PIN, SKEWER)) {
            if (motif !in motifs) continue
            lineDetail(motif, allyPieces, allyPositions, enemyPieces, enemyPositions)?.let { details[motif] = it }
        }
        if (HANGS_PIECE in motifs) {
            details[HANGS_PIECE] = "Your $movedName on $at is attacked and nothing defends it."
        }
        if (CAPTURE in motifs || RECAPTURE in motifs || MATERIAL_SWING in motifs) {
            val enemyBefore = if (white) stateBefore.positionsBlack else stateBefore.positionsWhite
            val enemyPiecesBefore = if (white) stateBefore.piecesBlack else stateBefore.piecesWhite
            val takenIndex = enemyBefore.indexOf(toSquare)
            if (takenIndex != -1) {
                val taken = enemyPiecesBefore[takenIndex].name.lowercase()
                if (MATERIAL_SWING in motifs) details[MATERIAL_SWING] = "It wins the $taken on $at."
                if (CAPTURE in motifs) details[CAPTURE] = "It takes the $taken on $at."
                if (RECAPTURE in motifs) details[RECAPTURE] = "It takes back on $at, levelling the trade."
            }
        }
        if (DEVELOPS in motifs && fromSquare != null) {
            details[DEVELOPS] = "It brings the $movedName out to $at."
        }
        if (CENTER_CONTROL in motifs) {
            val centres = (listOf(toSquare) + attacked).filter { it in CENTER_SQUARES }
                .map { UciMoveConverter.positionToUciSquare(it) }
                .distinct()
            if (centres.isNotEmpty()) {
                details[CENTER_CONTROL] = "Your $movedName covers ${centres.joinToString(" and ")}."
            }
        }
        return details
    }

    /**
     * Enemy pieces on [covered] that are actually worth attacking: undefended, or worth more than
     * the attacker.
     *
     * Shared by the detection and the sentence, so the coach can never name a target the detector
     * did not count — the mistake that produced a confident line about a defended bishop.
     */
    private fun winnableTargets(
        state: GameUiState,
        covered: List<Pair<Int, Int>>,
        movingSide: Set,
        attacker: Piece,
    ): List<Pair<Int, Int>> {
        val white = movingSide == Set.WHITE
        val enemySide = if (white) Set.BLACK else Set.WHITE
        val enemyPositions = if (white) state.positionsBlack else state.positionsWhite
        val enemyPieces = if (white) state.piecesBlack else state.piecesWhite
        return covered.filter { square ->
            val index = enemyPositions.indexOf(square)
            if (index == -1) return@filter false
            val target = enemyPieces[index]
            val undefended = SquareInsight.contest(state, square, enemySide).first == 0
            undefended || pieceValue(target) > pieceValue(attacker)
        }
    }

    /** Names both ends of a pin or skewer, which the ray scan above already had to identify. */
    private fun lineDetail(
        motif: String,
        allyPieces: List<Piece>,
        allyPositions: List<Pair<Int, Int>>,
        enemyPieces: List<Piece>,
        enemyPositions: List<Pair<Int, Int>>,
    ): String? {
        for (i in allyPieces.indices) {
            val attacker = allyPieces[i]
            if (attacker !is Bishop && attacker !is Rook && attacker !is Queen) continue
            for (dir in getRayDirections(attacker)) {
                val ray = castRay(allyPositions[i], dir, enemyPositions, allyPositions)
                val enemyHits = ray.filter { it in enemyPositions }
                if (enemyHits.size < 2 || ray.any { it in allyPositions }) continue
                val front = enemyPieces[enemyPositions.indexOf(enemyHits[0])]
                val back = enemyPieces[enemyPositions.indexOf(enemyHits[1])]
                // Same predicates as the detection above, or this would narrate a line the
                // detector already refused to call a pin.
                val matches = if (motif == PIN) isPin(front, back) else isSkewer(front, back)
                if (!matches) continue
                val attackerAt = UciMoveConverter.positionToUciSquare(allyPositions[i])
                val frontAt = UciMoveConverter.positionToUciSquare(enemyHits[0])
                val backAt = UciMoveConverter.positionToUciSquare(enemyHits[1])
                val verb = if (motif == PIN) "pins" else "skewers"
                return "Your ${attacker.name.lowercase()} on $attackerAt $verb the " +
                    "${front.name.lowercase()} on $frontAt against the ${back.name.lowercase()} on $backAt."
            }
        }
        return null
    }

    /**
     * The non-tactical half of the vocabulary.
     *
     * **Suppression matters even with [ALL_MOTIFS] ordering.** A general motif alongside its
     * specific counterpart — `threatens` with `fork`, `material-swing` with `recapture`,
     * `king-safety` with `castle-*` — is redundant at best and wrong at worst ("It wins material."
     * about a recapture that only restored balance). Priority stops the vaguer sentence being
     * *chosen*; suppression stops the claim being *made* at all, which also matters because the
     * whole motif list is handed to the model as facts it may narrate. Each rule is marked below.
     */
    private fun positionalMotifs(
        stateBefore: GameUiState,
        stateAfter: GameUiState,
        movingSide: Set,
        toSquare: Pair<Int, Int>,
        fromSquare: Pair<Int, Int>?,
        promoted: Boolean,
        previousToSquare: Pair<Int, Int>?,
        movedPiece: Piece,
        tactical: List<String>,
    ): List<String> {
        val motifs = mutableListOf<String>()
        val white = movingSide == Set.WHITE

        // FEN round-trips do not carry check state, so it is recomputed here rather than read off
        // stateAfter — the same reason SanConverter.sanForUci applies the move for its suffix.
        val resolved = applyWinConditions(stateAfter)
        val enemyInCheck = if (white) resolved.inCheckBlack else resolved.inCheckWhite
        val mated = resolved.winState == WinState.WHITE || resolved.winState == WinState.BLACK
        if (mated) motifs.add(CHECKMATE)
        // Checkmate is check, but saying "gives check" about a finished game is a downgrade.
        else if (enemyInCheck) motifs.add(CHECK)

        if (promoted) motifs.add(PROMOTION)

        val enemyBefore = if (white) stateBefore.positionsBlack else stateBefore.positionsWhite
        val enemyAfter = if (white) stateAfter.positionsBlack else stateAfter.positionsWhite
        // Counting pieces rather than testing occupancy catches en passant, where the captured pawn
        // was never on the destination square.
        val captured = enemyBefore.size > enemyAfter.size
        val isRecapture = captured && previousToSquare != null && previousToSquare == toSquare
        if (isRecapture) motifs.add(RECAPTURE) else if (captured) motifs.add(CAPTURE)

        val (defenders, attackers) = SquareInsight.contest(stateAfter, toSquare, movingSide)
        val hanging = attackers > 0 && defenders == 0
        if (hanging) motifs.add(HANGS_PIECE)

        // "Wins material" only when the capture is not immediately answerable, and never on a
        // recapture — restoring balance is not winning material. Suppressed by RECAPTURE, which
        // DeterministicCoach reaches after material-swing.
        if (captured && !isRecapture && !hanging) motifs.add(MATERIAL_SWING)

        if (fromSquare != null) {
            castlingRookMove(movedPiece, fromSquare, toSquare)?.let {
                // Column 6 is the g-file: the king's destination when castling short.
                motifs.add(if (toSquare.second > fromSquare.second) CASTLE_KINGSIDE else CASTLE_QUEENSIDE)
            }

            val backRank = if (white) BOARD_SIZE - 1 else 0
            val isMinor = movedPiece is Knight || movedPiece is Bishop
            if (isMinor && fromSquare.first == backRank) motifs.add(DEVELOPS)

            // King safety is claimed only for a king that stepped off a square the enemy attacked.
            // Castling already has its own, better sentence, and DeterministicCoach reaches
            // king-safety first — so emitting both would replace it.
            if (movedPiece is King && CASTLE_KINGSIDE !in motifs && CASTLE_QUEENSIDE !in motifs) {
                val (_, attackersBefore) = SquareInsight.contest(stateBefore, fromSquare, movingSide)
                if (attackersBefore > 0) motifs.add(KING_SAFETY)
            }
        }

        if (movedPiece is Pawn && !captured && !promoted) motifs.add(PAWN_PUSH)

        val coveredAfter = SquareInsight.attacked(stateAfter, movedPiece, toSquare)
        if (toSquare in CENTER_SQUARES || coveredAfter.any { it in CENTER_SQUARES }) {
            motifs.add(CENTER_CONTROL)
        }

        // Threatens is the general case of the four tactics, so it is suppressed when any applies.
        //
        // "Attacks an enemy piece" is not a threat. A rook on c1 sees a defended bishop on c8 down
        // an open file; taking it loses the exchange, and reporting "Your rook on c1 attacks the
        // bishop on c8." states a fact about the board that costs the player money to act on. A
        // threat has to be *winnable*: the target is undefended, or worth more than the attacker.
        val enemyPiecesAfter = if (white) stateAfter.piecesBlack else stateAfter.piecesWhite
        if (tactical.isEmpty() && winnableTargets(stateAfter, coveredAfter, movingSide, movedPiece).isNotEmpty()) {
            motifs.add(THREATENS)
        }

        // Defends: the moved piece now covers a friendly piece the enemy is attacking.
        val allyAfter = if (white) stateAfter.positionsWhite else stateAfter.positionsBlack
        val defendsSomething = coveredAfter.any { square ->
            square in allyAfter && SquareInsight.contest(stateAfter, square, movingSide).second > 0
        }
        if (defendsSomething) motifs.add(DEFENDS)

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

    /**
     * A pin worth naming: the shielded piece behind must be one the player would actually refuse to
     * expose — a rook, queen or king.
     *
     * Value order alone is not enough. Bb5 in the Ruy Lopez lines up on the knight at c6 with the
     * **pawn** on d7 behind it, and "front is cheaper than back" called that a pin against a pawn.
     * That was tolerable while the sentence was the generic "It pins an enemy piece against a more
     * valuable one." and became a confidently wrong claim the moment the coach started naming the
     * squares. A specific wrong sentence is worse than a vague true one.
     */
    private fun isPin(front: Piece, back: Piece): Boolean =
        pieceValue(front) < pieceValue(back) && pieceValue(back) >= pieceValue(Rook(Set.WHITE))

    /**
     * A skewer worth naming: the piece behind must be worth capturing once the front one steps
     * aside. Winning a pawn this way is not a skewer in any sense the player benefits from hearing.
     */
    private fun isSkewer(front: Piece, back: Piece): Boolean =
        pieceValue(front) > pieceValue(back) && back !is Pawn

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
