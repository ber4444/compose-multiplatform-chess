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
        gameViewModel.onMoveCoached = { fenBefore, moveRecord ->
            if (gameViewModel.aiCoachEnabled && orchestrator != null) {
                triggerCoach(fenBefore, moveRecord)
            }
        }
    }

    fun attachCoachOrchestrator(orchestrator: AiCoachOrchestrator?) {
        coachJob?.cancel()
        this.orchestrator = orchestrator
        // Attaching is the "coach settled" signal: the entry point calls this only after warmup /
        // availability resolves (Android: model downloaded + loaded; iOS: Foundation Models checked).
        // Clear the transient LoadingModel placeholder ("Downloading…" / "Starting Gemma engine…") so a
        // ready coach doesn't sit behind a stale loading message. The panel stays Hidden until the
        // first coached move drives it via triggerCoach(). (This is safe now that entry points attach
        // post-warmup — the earlier bug was attaching *during* load, which wiped the loading state.)
        _coachUiState.value = MoveCoachUiState.Hidden
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

    private fun triggerCoach(fenBefore: String, moveRecord: com.example.myapplication.MoveRecord) {
        val orchestrator = this.orchestrator ?: return
        coachJob?.cancel()

        val request = com.example.ondeviceai.MoveCoachRequest(
            moveUci = moveRecord.uci,
            moveDisplay = moveRecord.san,
            deterministicHeadline = DeterministicCoach.buildHeadline(moveRecord),
            deterministicExplanation = DeterministicCoach.buildExplanation(moveRecord),
            engineDifficultyName = engineDifficultyName,
        )

        _coachUiState.value = MoveCoachUiState.Loading(request.moveDisplay)
        coachJob = scope.launch {
            try {
                orchestrator.explainMoveStreaming(request).collect { event ->
                    when (event) {
                        is MoveCoachEvent.Streaming ->
                            _coachUiState.value = MoveCoachUiState.Streaming(
                                move = request.moveDisplay,
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
