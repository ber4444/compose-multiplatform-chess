package com.example.myapplication.explorer

import com.example.myapplication.OpponentMoveChooser
import kotlin.random.Random

/**
 * Maia-3 as the opponent: it plays the way players in [band] play, not the best move.
 *
 * It **samples** from the predicted distribution rather than always playing the most likely move,
 * because that is what makes it play like a person at that level. `maia3-uci` defaults to sampling
 * at temperature 1 too. The always-most-likely move is a consensus of thousands of players and plays
 * noticeably above the band it was asked about. Sampling is limited to the predictor's top moves, so
 * a one-in-a-thousand legal move never gets played.
 *
 * Only the move choice is Maia's. Stockfish stays attached for hints, coach assessments and draw
 * offers.
 */
class MaiaOpponent(
    private val predictor: HumanMovePredictor,
    private val band: RatingBand,
    private val random: Random = Random.Default,
) : OpponentMoveChooser {
    override val name: String = "Maia-3 (${band.label})"

    override suspend fun chooseMove(fenHistory: List<String>): String? {
        val position = ExplorerPosition(fenHistory.last(), fenHistory.takeLast(MAIA_HISTORY))
        val moves = predictor.predict(position, band)?.moves.orEmpty()
        if (moves.isEmpty()) return null
        var pick = random.nextDouble() * moves.sumOf { it.share }
        for (move in moves) {
            pick -= move.share
            if (pick < 0) return move.uci
        }
        return moves.last().uci
    }
}
