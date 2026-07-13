package com.example.myapplication.movecoach

import co.touchlab.kermit.Logger
import com.example.myapplication.GameUiState
import com.example.myapplication.GameViewModel
import com.example.myapplication.PromotionType
import com.example.myapplication.Set
import com.example.ondeviceai.AiCoachOrchestrator
import com.example.ondeviceai.MoveCoachEvent
import com.example.ondeviceai.MoveCoachResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Manages the AI Move Coach state and orchestration, decoupled from the pure chess logic
 * in GameViewModel.
 */
class MoveCoachManager(
    private val gameViewModel: GameViewModel,
    private val engineDifficultyName: String = "MEDIUM"
) {
    private val logger = Logger.withTag("MoveCoachManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _coachUiState = MutableStateFlow<MoveCoachUiState>(MoveCoachUiState.Hidden)
    val coachUiState: StateFlow<MoveCoachUiState> = _coachUiState

    private var coachJob: Job? = null
    private var orchestrator: AiCoachOrchestrator? = null

    init {
        // Register the callback to automatically trigger the coach on engine moves
        gameViewModel.onMoveCoached = { stateBefore, stateAfter, movingPieceSide, fromSquare, toSquare, promotionType ->
            if (gameViewModel.aiCoachEnabled && orchestrator != null) {
                triggerCoach(
                    stateBefore = stateBefore,
                    stateAfter = stateAfter,
                    movingPieceSide = movingPieceSide,
                    fromSquare = fromSquare,
                    toSquare = toSquare,
                    promotionType = promotionType,
                )
            }
        }
    }

    fun attachCoachOrchestrator(orchestrator: AiCoachOrchestrator?) {
        coachJob?.cancel()
        this.orchestrator = orchestrator
        _coachUiState.value = if (orchestrator == null) MoveCoachUiState.Hidden else MoveCoachUiState.Hidden
    }

    /**
     * Platform glue helper: set the coach panel state directly. Used while the
     * local model is being unpacked / initialized (when there's no orchestrator
     * yet to drive the state via events).
     */
    fun setCoachModelState(state: MoveCoachUiState) {
        coachJob?.cancel()
        _coachUiState.value = state
    }
    
    fun hideWindow() {
        coachJob?.cancel()
        _coachUiState.value = MoveCoachUiState.Hidden
    }

    private fun triggerCoach(
        stateBefore: GameUiState,
        stateAfter: GameUiState,
        movingPieceSide: Set,
        fromSquare: Pair<Int, Int>,
        toSquare: Pair<Int, Int>,
        promotionType: PromotionType?,
    ) {
        val orchestrator = this.orchestrator ?: return
        coachJob?.cancel()

        val request = MoveCoachContextExtractor.build(
            stateBefore = stateBefore,
            stateAfter = stateAfter,
            movingPieceSide = movingPieceSide,
            fromSquare = fromSquare,
            toSquare = toSquare,
            promotionType = promotionType,
            evaluationBeforeCp = null,
            evaluationAfterCp = null,
            engineDifficultyName = engineDifficultyName,
        )

        _coachUiState.value = MoveCoachUiState.Loading(request.bestMoveDisplay)
        coachJob = scope.launch {
            try {
                orchestrator.explainMoveStreaming(request).collect { event ->
                    when (event) {
                        is MoveCoachEvent.Streaming ->
                            _coachUiState.value = MoveCoachUiState.Streaming(
                                move = request.bestMoveDisplay,
                                text = event.partialText,
                            )
                        is MoveCoachEvent.Complete -> when (val result = event.result) {
                            is MoveCoachResult.Success ->
                                _coachUiState.value = MoveCoachUiState.Ready(result.explanation)
                            is MoveCoachResult.FellBack ->
                                _coachUiState.value = MoveCoachUiState.Fallback(result.text, result.reason)
                            is MoveCoachResult.Failed ->
                                _coachUiState.value = MoveCoachUiState.Error(result.message)
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logger.w(t) { "Coach orchestrator failed" }
                _coachUiState.value = MoveCoachUiState.Error(t.message ?: "coach failed")
            }
        }
    }

    fun close() {
        coachJob?.cancel()
        gameViewModel.onMoveCoached = null
        scope.cancel()
    }
}
