package com.example.myapplication.persistence

import co.touchlab.kermit.Logger
import com.example.myapplication.ChessEngine
import com.example.myapplication.FenConverter
import com.example.myapplication.GameUiState
import com.example.myapplication.MotifDetector
import com.example.myapplication.MoveAssessor
import com.example.myapplication.SanConverter
import com.example.myapplication.UciMoveConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.myapplication.Set
import com.example.myapplication.MoveRecord

/**
 * Iterates over stored historical games in [GameHistoryRepository] and annotates White's unassessed
 * moves in the background using the attached [ChessEngine].
 */
class GameHistoryBackfiller(
    private val repository: GameHistoryRepository,
    private val engine: ChessEngine
) {
    private val logger = Logger.withTag("GameHistoryBackfiller")
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                try {
                    backfillNext()
                } catch (e: Exception) {
                    logger.w(e) { "Backfill iteration failed" }
                }
                delay(2000) // Poll gently
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun backfillNext() {
        val games = repository.games.value
        for (game in games) {
            val unassessedIndex = game.moveRecords.indexOfFirst {
                // White moves only, and only those lacking an assessment
                val isWhiteMove = game.moveRecords.indexOf(it) % 2 == 0
                isWhiteMove && it.assessment == null
            }

            if (unassessedIndex != -1) {
                logger.i { "Backfilling assessment for game ${game.id} ply $unassessedIndex" }
                val record = game.moveRecords[unassessedIndex]
                
                // If the engine didn't provide cpAfter during gameplay, we evaluate fenAfter.
                // We evaluate from White's perspective. fenAfter is Black to move, so evaluate returns from Black's perspective.
                // We must negate it to get White's perspective.
                val cpPlayed = record.cpAfter ?: engine.evaluate(record.fenAfter)?.let { -it }
                if (cpPlayed == null) return

                val fenBefore = if (unassessedIndex == 0) {
                    FenConverter.gameStateToFen(GameUiState())
                } else {
                    game.moveRecords[unassessedIndex - 1].fenAfter
                }
                // One search does both jobs, as runIdleAnalysis already does for live games: the
                // score it reports *is* the eval of the best move, and its UCI is what the coach
                // needs to name the alternative. This used to call evaluate(), which answers the
                // first question and discards the second — so a backfilled game could never offer a
                // "X was stronger" line, and the difference was invisible because the assessment
                // otherwise looked complete.
                val bestMoveResult = engine.getBestMove(fenBefore)
                val cpBest = bestMoveResult?.evaluationCp ?: engine.evaluate(fenBefore) ?: return

                val moveAppFormat = UciMoveConverter.uciMoveToAppMove(record.uci, emptyList())
                val motifs = if (moveAppFormat != null) {
                    val stateBefore = FenConverter.fenToGameState(fenBefore)
                    val stateAfter = FenConverter.fenToGameState(record.fenAfter)
                    MotifDetector.detectMotifs(
                        stateBefore = stateBefore,
                        stateAfter = stateAfter,
                        movingSide = Set.WHITE,
                        toSquare = moveAppFormat.position,
                        fromSquare = UciMoveConverter.parseUciMove(record.uci).first,
                        promoted = record.uci.length > 4,
                        previousToSquare = game.moveRecords.getOrNull(unassessedIndex - 1)
                            ?.let { UciMoveConverter.parseUciMove(it.uci).second },
                    )
                } else emptyList()

                val assessment = MoveAssessor.assessMove(
                    cpBefore = cpBest,
                    cpPlayed = cpPlayed,
                    cpBest = cpBest,
                    motifs = motifs,
                    bestMoveUci = bestMoveResult?.uci,
                    bestMoveSan = bestMoveResult?.uci
                        ?.takeIf { it != record.uci }
                        ?.let { SanConverter.sanForUci(FenConverter.fenToGameState(fenBefore), it) },
                )

                val updatedRecords = game.moveRecords.toMutableList()
                updatedRecords[unassessedIndex] = record.copy(assessment = assessment)
                
                val updatedGame = game.copy(moveRecords = updatedRecords)
                repository.update(updatedGame)
                return // Only do one per iteration to avoid starving the CPU
            }
        }
    }
}
