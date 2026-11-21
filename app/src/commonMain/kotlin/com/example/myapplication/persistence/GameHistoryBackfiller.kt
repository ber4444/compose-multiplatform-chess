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
 * Iterates over stored historical games in [GameHistoryRepository] and annotates the player's
 * unassessed moves in the background using the attached [ChessEngine].
 *
 * Which plies are the player's follows [SavedGame.playerSide], the same rule
 * `GameViewModel.runIdleAnalysis` applies live: even-index plies are White's, and the player is
 * White only when the saved game says so. Games saved before that field existed default to
 * `"WHITE"`, which was the only side selectable at the time.
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

    /** `internal` rather than `private` so `GameHistoryBackfillerTest` can drive one iteration directly instead of racing the real `start()`/`delay` loop. */
    internal suspend fun backfillNext() {
        val games = repository.games.value
        for (game in games) {
            val playerIsWhite = game.playerSide != "BLACK"
            val unassessedIndex = game.moveRecords.indexOfFirst { record ->
                // The player's moves only, and only those lacking an assessment.
                val isPlayerMove = (game.moveRecords.indexOf(record) % 2 == 0) == playerIsWhite
                isPlayerMove && record.assessment == null
            }

            if (unassessedIndex != -1) {
                logger.i { "Backfilling assessment for game ${game.id} ply $unassessedIndex" }
                val record = game.moveRecords[unassessedIndex]
                val moverIsWhite = unassessedIndex % 2 == 0

                // If the engine didn't provide cpAfter during gameplay, evaluate fenAfter instead.
                // engine.evaluate() already normalizes to White's perspective (UciEvaluation
                // .toWhitePerspective, applied by every transport) — it does not need negating here.
                // This used to negate unconditionally, which silently inverted every backfilled
                // assessment that hit this fallback (any game played without a live-attached engine,
                // e.g. the CPU fallback) into its opposite: a blunder recorded as excellent and vice
                // versa.
                val cpPlayed = record.cpAfter ?: engine.evaluate(record.fenAfter)
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
                val detected = if (moveAppFormat != null) {
                    val stateBefore = FenConverter.fenToGameState(fenBefore)
                    val stateAfter = FenConverter.fenToGameState(record.fenAfter)
                    MotifDetector.detectDetailed(
                        stateBefore = stateBefore,
                        stateAfter = stateAfter,
                        movingSide = if (moverIsWhite) Set.WHITE else Set.BLACK,
                        toSquare = moveAppFormat.position,
                        fromSquare = UciMoveConverter.parseUciMove(record.uci).first,
                        promoted = record.uci.length > 4,
                        previousToSquare = game.moveRecords.getOrNull(unassessedIndex - 1)
                            ?.let { UciMoveConverter.parseUciMove(it.uci).second },
                    )
                } else MotifDetector.Detected(emptyList(), emptyMap())

                val assessment = MoveAssessor.assessMove(
                    cpBefore = cpBest,
                    cpPlayed = cpPlayed,
                    cpBest = cpBest,
                    motifs = detected.motifs,
                    motifDetails = detected.details,
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
