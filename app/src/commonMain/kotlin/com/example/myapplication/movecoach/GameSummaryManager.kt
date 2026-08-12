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
    /** The last request built by [triggerSummary], replayed by [retry]. */
    private var lastRequest: GameSummaryRequest? = null

    /**
     * Whether a missing orchestrator still produces a summary.
     *
     * `attachOrchestrator(null)` means "this build cannot summarise" and hides the button. Android
     * needs the opposite: no model, but a complete deterministic summary. Kept as a separate
     * opt-in rather than making null always deterministic, so desktop and wasm without
     * `CHESS_ENABLE_COACH=1` keep hiding the button as before.
     */
    private var deterministicEnabled = false

    /** Android: no model, and the composed turning points are the summary. */
    fun enableDeterministic() {
        orchestrator = null
        deterministicEnabled = true
        _uiState.value = GameSummaryUiState.Hidden
    }

    /**
     * Attaches (or clears) the orchestrator. A `null` orchestrator — coach-disabled desktop/web and
     * Foundation-Models-unavailable iOS — moves the UI to [GameSummaryUiState.Unavailable] so the
     * trigger button doesn't render (pressing it would otherwise be a silent no-op). Entry points
     * that never call this at all leave the manager at its default [GameSummaryUiState.Unavailable],
     * so this is not the only path to that state.
     *
     * Android takes [enableDeterministic] instead: no model, but a real summary.
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
        val request = GameSummaryRequest(
            pgn = pgn,
            moveHistory = moveHistory,
            playerSide = playerSide,
            engineDifficultyName = engineDifficultyName
        )
        lastRequest = request
        launchSummary(request)
    }

    /** Re-run the most recent summary. Surfaced only by [FallbackPresentation.Retryable]. */
    fun retry() {
        launchSummary(lastRequest ?: return)
    }

    private fun launchSummary(request: GameSummaryRequest) {
        val orchestrator = this.orchestrator
        generationJob?.cancel()

        // No model on this platform: compose the turning points directly. Same reasoning as
        // MoveCoachManager's deterministic path — the facts are the product, and a summary that
        // names every turning point instantly beats one a model takes 20 s to get wrong. See
        // docs/benchmarks/on-device-ai/android-model-latency-2026-08.md.
        if (orchestrator == null) {
            if (!deterministicEnabled) return
            _uiState.value = GameSummaryUiState.Ready(
                com.example.ondeviceai.GameSummaryExplanation(
                    explanation = CitationSanitizer.sanitize(
                        com.example.ondeviceai.GameSummaryGrounding.composeFor(request),
                    ),
                    route = DETERMINISTIC_ROUTE,
                    metrics = com.example.ondeviceai.AiInferenceMetrics(
                        firstTokenMs = null,
                        completeMs = 0L,
                        tokenCount = 0,
                        route = DETERMINISTIC_ROUTE,
                    ),
                ),
            )
            return
        }

        _uiState.value = GameSummaryUiState.Loading
        generationJob = scope.launch {
            try {
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
                                    explanation = result.explanation.copy(
                                        explanation = CitationSanitizer.sanitize(result.explanation.explanation),
                                    ),
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

    private companion object {
        /**
         * Provenance of a composed summary: the app wrote it, from the engine's own assessments.
         * `NoLocalModel` rather than a free-tier reason — the platform has no model to unlock.
         */
        private val DETERMINISTIC_ROUTE = com.example.ondeviceai.AiRoute.Fallback(
            com.example.ondeviceai.AiRoutePolicyDecider.FallbackReason.NoLocalModel,
        )
    }
}
