package com.example.myapplication.movecoach

import co.touchlab.kermit.Logger
import com.example.myapplication.ui.CitationSanitizer
import com.example.ondeviceai.GameSummaryEvent
import com.example.ondeviceai.GameSummaryOrchestrator
import com.example.ondeviceai.GameSummaryRequest
import com.example.ondeviceai.GameSummaryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GameSummaryManager {
    private val logger = Logger.withTag("GameSummaryManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow<GameSummaryUiState>(GameSummaryUiState.Unavailable)
    val uiState: StateFlow<GameSummaryUiState> = _uiState

    private var orchestrator: GameSummaryOrchestrator? = null
    private var generationJob: Job? = null

    /**
     * Attaches (or clears) the orchestrator. A `null` orchestrator — the release-Android,
     * coach-disabled-desktop/web, and Foundation-Models-unavailable-iOS cases — moves the UI to
     * [GameSummaryUiState.Unavailable] so the trigger button doesn't render (pressing it would
     * otherwise be a silent no-op). Entry points that never call this at all (desktop without
     * `CHESS_ENABLE_COACH=1`, web without `?coach=1`) leave the manager at its default
     * [GameSummaryUiState.Unavailable] state, so this is not the only path to that state.
     */
    fun attachOrchestrator(orchestrator: GameSummaryOrchestrator?) {
        this.orchestrator = orchestrator
        generationJob?.cancel()
        _uiState.value = if (orchestrator != null) GameSummaryUiState.Hidden else GameSummaryUiState.Unavailable
    }

    fun hide() {
        generationJob?.cancel()
        _uiState.value = GameSummaryUiState.Hidden
    }

    fun triggerSummary(
        pgn: String,
        moveHistory: List<com.example.myapplication.MoveRecord>,
        playerSide: com.example.myapplication.Set,
        engineDifficultyName: String
    ) {
        val orchestrator = this.orchestrator ?: return
        generationJob?.cancel()

        _uiState.value = GameSummaryUiState.Loading
        generationJob = scope.launch {
            try {
                val request = GameSummaryRequest(
                    pgn = pgn,
                    moveHistory = moveHistory,
                    playerSide = playerSide,
                    engineDifficultyName = engineDifficultyName
                )
                orchestrator.summarizeGameStreaming(request).collect { event ->
                    when (event) {
                        is GameSummaryEvent.Streaming ->
                            _uiState.value = GameSummaryUiState.Streaming(
                                CitationSanitizer.sanitizeStreaming(event.partialText)
                            )
                        // This surface runs with no response validator (any non-blank text is
                        // accepted), so the sanitizer is the only thing between raw model output
                        // and the user.
                        is GameSummaryEvent.Complete -> when (val result = event.result) {
                            is GameSummaryResult.Success ->
                                _uiState.value = GameSummaryUiState.Ready(
                                    result.explanation.copy(
                                        explanation = CitationSanitizer.sanitize(result.explanation.explanation),
                                    )
                                )
                            is GameSummaryResult.FellBack ->
                                _uiState.value = GameSummaryUiState.Fallback(
                                    CitationSanitizer.sanitize(result.text),
                                    result.reason,
                                )
                            is GameSummaryResult.Failed ->
                                _uiState.value = GameSummaryUiState.Error(result.message)
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logger.w(t) { "Game summary orchestrator failed" }
                _uiState.value = GameSummaryUiState.Error(t.message ?: "summary failed")
            }
        }
    }

    fun close() {
        generationJob?.cancel()
        scope.cancel()
    }
}
