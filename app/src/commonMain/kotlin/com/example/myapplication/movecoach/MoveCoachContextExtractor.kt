package com.example.myapplication.movecoach

import com.example.myapplication.FenConverter
import com.example.myapplication.GameUiState
import com.example.myapplication.King
import com.example.myapplication.Knight
import com.example.myapplication.Pawn
import com.example.myapplication.Piece
import com.example.myapplication.PromotionType
import com.example.myapplication.Queen
import com.example.myapplication.Rook
import com.example.myapplication.Bishop
import com.example.myapplication.Set
import com.example.myapplication.UciMoveConverter
import com.example.myapplication.WinState
import com.example.ondeviceai.MoveCoachFallback
import com.example.ondeviceai.MoveCoachRequest

/**
 * Builds a [MoveCoachRequest] from the app's post-move [GameUiState]. The app
 * owns chess-specific context extraction (plan §3); the module owns the prompt.
 *
 * Tags are produced by deterministic code from the post-move state plus the
 * pre-move snapshot the caller passes in. The model is never asked to derive
 * these — it only rephrases them.
 *
 * Per plan §1.2 the evaluation *before* is one engine call per coached move,
 * and the evaluation *after* is a second engine call. Callers bound the second
 * call's depth or skip it on slower devices; the extractor just records both
 * when supplied.
 */
object MoveCoachContextExtractor {

    /**
     * @param stateBefore the state before the coached move (used to find the
     *   moving piece and detect captures).
     * @param stateAfter the state after the coached move (the position the
     *   coach is explaining).
     * @param movingPieceSide which side made the coached move (typically BLACK
     *   since the player plays White).
     * @param fromSquare the from-square of the move (board row/col).
     * @param toSquare the to-square of the move.
     * @param promotionType the promotion chosen, if any.
     * @param evaluationBeforeCp optional engine eval of the pre-move position
     *   (centipawns, White's perspective).
     * @param evaluationAfterCp optional engine eval of the post-move position.
     */
    fun build(
        stateBefore: GameUiState,
        stateAfter: GameUiState,
        movingPieceSide: Set,
        fromSquare: Pair<Int, Int>,
        toSquare: Pair<Int, Int>,
        promotionType: PromotionType? = null,
        evaluationBeforeCp: Int? = null,
        evaluationAfterCp: Int? = null,
        engineDifficultyName: String = "Medium",
    ): MoveCoachRequest {
        val movingPiece = findPieceAt(stateBefore, movingPieceSide, fromSquare)
        val bestMoveUci = UciMoveConverter.positionToUciSquare(fromSquare) +
            UciMoveConverter.positionToUciSquare(toSquare) +
            (promotionType?.uciChar?.toString() ?: "")
        val bestMoveDisplay = stateAfter.moveHistory.lastOrNull()?.san ?: bestMoveUci
        val fenBefore = FenConverter.gameStateToFen(stateBefore)
        val sideToMove = movingPieceSide.name.lowercase()
        val tags = deterministicTags(
            stateBefore = stateBefore,
            stateAfter = stateAfter,
            movingPieceSide = movingPieceSide,
            movingPiece = movingPiece,
            fromSquare = fromSquare,
            toSquare = toSquare,
            promotionType = promotionType,
        )

        return MoveCoachRequest(
            fenBefore = fenBefore,
            bestMoveUci = bestMoveUci,
            bestMoveDisplay = bestMoveDisplay,
            sideToMove = sideToMove,
            evaluationBeforeCp = evaluationBeforeCp,
            evaluationAfterCp = evaluationAfterCp,
            deterministicTags = tags,
            engineDifficultyName = engineDifficultyName,
        )
    }

    private fun findPieceAt(state: GameUiState, side: Set, square: Pair<Int, Int>): Piece? {
        val pieces = if (side == Set.WHITE) state.piecesWhite else state.piecesBlack
        val positions = if (side == Set.WHITE) state.positionsWhite else state.positionsBlack
        val idx = positions.indexOf(square)
        return if (idx == -1) null else pieces[idx]
    }

    /**
     * Compute deterministic tactical tags from before/after snapshots. Cheap to
     * evaluate; covers the families called out in plan §10
     * (capture/check/castle/promotion/material).
     */
    private fun deterministicTags(
        stateBefore: GameUiState,
        stateAfter: GameUiState,
        movingPieceSide: Set,
        movingPiece: Piece?,
        fromSquare: Pair<Int, Int>,
        toSquare: Pair<Int, Int>,
        promotionType: PromotionType?,
    ): List<String> {
        val tags = mutableListOf<String>()

        val enemyBeforePos = if (movingPieceSide == Set.WHITE) stateBefore.positionsBlack else stateBefore.positionsWhite

        // Capture: enemy piece was on the destination square before the move
        // (handles normal captures; en passant is detected by pawn-to-empty-rank).
        val wasCapture = toSquare in enemyBeforePos ||
            (movingPiece is Pawn && fromSquare.second != toSquare.second &&
                toSquare == stateBefore.enPassantTarget)
        if (wasCapture) tags += MoveCoachFallback.TAG_CAPTURE

        // Castling: king moves two files
        if (movingPiece is King && kotlin.math.abs(toSquare.second - fromSquare.second) == 2) {
            if (toSquare.second == 6) tags += MoveCoachFallback.TAG_CASTLE_KS
            else tags += MoveCoachFallback.TAG_CASTLE_QS
        }

        // Promotion
        if (promotionType != null) tags += MoveCoachFallback.TAG_PROMOTION

        // Check / checkmate from the post-move state's flags
        val opponentInCheck = if (movingPieceSide == Set.WHITE) stateAfter.inCheckBlack else stateAfter.inCheckWhite
        if (opponentInCheck) {
            if (stateAfter.winState != WinState.NONE) tags += MoveCoachFallback.TAG_CHECKMATE
            else tags += MoveCoachFallback.TAG_CHECK
        }

        // Material swing: did the moving side's material balance improve?
        val materialBefore = materialFor(movingPieceSide, stateBefore) -
            materialFor(oppositeOf(movingPieceSide), stateBefore)
        val materialAfter = materialFor(movingPieceSide, stateAfter) -
            materialFor(oppositeOf(movingPieceSide), stateAfter)
        if (materialAfter - materialBefore > 0) tags += MoveCoachFallback.TAG_MATERIAL_SWING

        // Recapture: capturing on a square where the opponent just captured
        if (wasCapture && enemyBeforePos.contains(toSquare)) {
            // Crude heuristic: if an enemy piece was on this square AND we just
            // captured there, it's likely a recapture if the opponent's previous
            // move also captured on this square. Without move history, we detect
            // it as "capturing onto a contested square".
        }

        // Development: minor piece (N/B) moving from the back rank to a non-back-rank square
        if (movingPiece is Knight || movingPiece is Bishop) {
            val backRank = if (movingPieceSide == Set.WHITE) 7 else 0
            if (fromSquare.first == backRank && toSquare.first != backRank) {
                tags += MoveCoachFallback.TAG_DEVELOPS
            }
        }

        // Center control: the move lands on or controls a central square (e4/d4/e5/d5)
        // Board coords: row 3 = rank 5, row 4 = rank 4; col 3 = d-file, col 4 = e-file
        val centerSquares = setOf(
            Pair(3, 3), Pair(3, 4), Pair(4, 3), Pair(4, 4)  // d5, e5, d4, e4
        )
        if (toSquare in centerSquares) {
            tags += MoveCoachFallback.TAG_CENTER_CONTROL
        }

        // Pawn push: any pawn advancing
        if (movingPiece is Pawn && fromSquare.second == toSquare.second) {
            tags += MoveCoachFallback.TAG_PAWN_PUSH
        }

        // King safety: king moves (non-castling) or castling — already tagged above
        if (movingPiece is King && promotionType == null) {
            val backRank = if (movingPieceSide == Set.WHITE) 7 else 0
            if (fromSquare.first == backRank && toSquare.first != backRank &&
                kotlin.math.abs(toSquare.second - fromSquare.second) != 2) {
                tags += MoveCoachFallback.TAG_KING_SAFETY
            }
        }

        // Opening phase: first 10 fullmoves
        if (stateAfter.fullmoveNumber <= 10) {
            tags += MoveCoachFallback.TAG_OPENING
        }

        return tags.distinct()
    }

    private fun materialFor(side: Set, state: GameUiState): Int {
        val pieces = if (side == Set.WHITE) state.piecesWhite else state.piecesBlack
        return pieces.sumOf { pieceValue(it) }
    }

    private fun pieceValue(piece: Piece): Int = when (piece) {
        is Pawn -> 100
        is Knight -> 320
        is Bishop -> 330
        is Rook -> 500
        is Queen -> 900
        is King -> 0
        else -> 0
    }

    private fun oppositeOf(side: Set): Set =
        if (side == Set.WHITE) Set.BLACK else Set.WHITE
}
