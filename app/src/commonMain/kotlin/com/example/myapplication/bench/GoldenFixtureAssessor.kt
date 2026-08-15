package com.example.myapplication.bench

import com.example.myapplication.ChessEngine
import com.example.myapplication.FenConverter
import com.example.myapplication.MotifDetector
import com.example.myapplication.MoveAssessor
import com.example.myapplication.MoveRecord
import com.example.myapplication.PromotionType
import com.example.myapplication.SanConverter
import com.example.myapplication.Set
import com.example.myapplication.UciMoveConverter
import com.example.myapplication.applyMove
import com.example.myapplication.movecoach.DeterministicCoach
import com.example.ondeviceai.MoveCoachRequest

/**
 * Builds a golden fixture's [MoveCoachRequest] the way the app builds a real one.
 *
 * **Why this exists.** The bench used to construct every request with a hardcoded
 * `deterministicExplanation = "This was a strong move."` and leave `moveClassName`, `motifs`,
 * `winPercentLost` and `betterMoveDisplay` at their defaults. `MoveCoachPromptBuilder.userPrompt`
 * emits each fact line only when its field is set, so the prompt the model actually received was:
 *
 * ```
 * The player just played Nh3.
 * Baseline explanation: "This was a strong move."
 *
 * Using only the facts above, tell the player in 1-2 short, conversational sentences
 * why Nh3 was that good or bad. Do not invent other moves, squares, or evaluations.
 * ```
 *
 * No assessment, no motifs, no better move, no win-percentage delta — and an explicit instruction
 * not to invent any. Every model benchmarked through that harness was asked to be specific about a
 * position it had been told nothing about, and forbidden from filling the gap. "This controls the
 * center." is close to the best available answer to that prompt, which means every quality verdict
 * on this page — `aicore-nano-fast` and `cactus-android` alike — measured the harness.
 *
 * This mirrors `MoveCoachManager.triggerCoach`, which is the one place that mapping is authoritative:
 * assess the ply, wrap it in a [MoveRecord], and let [DeterministicCoach] and the four fact fields
 * come off the assessment. Nothing here is bench-specific except where the position comes from.
 *
 * **A limitation this was expected to have, and does not.** The golden set's `bestMoveUci` is the
 * move being coached, so every case was expected to assess as BEST with ~0 centipawn loss and a null
 * `betterMoveDisplay`, exercising only the "why was this good" half of the coach. The 2026-08-15
 * Pixel 10 Pro XL run measured otherwise: 57 BEST, 12 EXCELLENT, 10 GOOD, 10 INACCURACY, 11 MISTAKE,
 * and 78 of 100 rows carrying a better move. The golden set's move is the *book* move; Stockfish at
 * HARD disagrees with it often enough that the "here is what you missed" half is covered too. No
 * deliberately sub-optimal golden cases are needed.
 */
suspend fun assessGoldenCase(
    engine: ChessEngine,
    fen: String,
    playedUci: String,
    playedSan: String,
    thinkTimeMs: Long,
): MoveCoachRequest? {
    val stateBefore = runCatching { FenConverter.fenToGameState(fen) }.getOrNull() ?: return null
    val movingSide = stateBefore.turn

    val (fromSquare, toSquare) = runCatching { UciMoveConverter.parseUciMove(playedUci) }
        .getOrNull() ?: return null

    val allyPositions =
        if (movingSide == Set.WHITE) stateBefore.positionsWhite else stateBefore.positionsBlack
    val pieceIndex = allyPositions.indexOf(fromSquare).takeIf { it >= 0 } ?: return null

    val promoted = playedUci.length > 4
    val promotion = if (promoted) PromotionType.entries.firstOrNull { it.uciChar == playedUci[4] } else null

    val stateAfter = runCatching { applyMove(stateBefore, pieceIndex, toSquare, promotion) }
        .getOrNull() ?: return null
    val fenAfter = FenConverter.gameStateToFen(stateAfter)

    // Both evaluations are from White's perspective, which is what MoveAssessor expects — the same
    // convention runIdleAnalysis relies on.
    val cpPlayed = engine.evaluate(fenAfter, thinkTimeMs) ?: return null
    val bestMoveResult = engine.getBestMove(fen, thinkTimeMs)
    // One search does both jobs where it can: its score is the eval of the best move and its UCI
    // names the alternative. Fall back to a plain evaluation of the pre-move position when the
    // search reported no score — losing this loses the assessment, and with it the whole point of
    // the run.
    val cpBest = bestMoveResult?.evaluationCp ?: engine.evaluate(fen, thinkTimeMs) ?: return null

    val detected = MotifDetector.detectDetailed(
        stateBefore = stateBefore,
        stateAfter = stateAfter,
        movingSide = movingSide,
        toSquare = toSquare,
        fromSquare = fromSquare,
        promoted = promoted,
        // Golden cases are single positions with no preceding ply, so nothing can read as a
        // recapture. Passing null is correct here rather than merely convenient.
        previousToSquare = null,
    )

    val assessment = MoveAssessor.assessMove(
        // By definition the eval of the position before the move is the eval of the best move.
        cpBefore = cpBest,
        cpPlayed = cpPlayed,
        cpBest = cpBest,
        motifs = detected.motifs,
        motifDetails = detected.details,
        bestMoveUci = bestMoveResult?.uci,
        bestMoveSan = bestMoveResult?.uci
            ?.takeIf { it != playedUci }
            ?.let { SanConverter.sanForUci(stateBefore, it) },
    )

    val record = MoveRecord(
        uci = playedUci,
        san = playedSan,
        fenAfter = fenAfter,
        cpAfter = cpPlayed,
        assessment = assessment,
    )

    return MoveCoachRequest(
        moveUci = playedUci,
        moveDisplay = playedSan,
        deterministicHeadline = DeterministicCoach.buildHeadline(record),
        deterministicExplanation = DeterministicCoach.buildExplanation(record, movingSide),
        engineDifficultyName = "Hard",
        moveClassName = assessment.moveClass.name,
        motifs = assessment.motifs,
        winPercentLost = assessment.winPercentLost(movingSide),
        betterMoveDisplay = assessment.bestMoveSan,
    )
}
