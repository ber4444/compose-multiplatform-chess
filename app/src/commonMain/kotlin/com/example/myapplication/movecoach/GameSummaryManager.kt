package com.example.myapplication.movecoach

import co.touchlab.kermit.Logger
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

    private val _uiState = MutableStateFlow<GameSummaryUiState>(GameSummaryUiState.Hidden)
    val uiState: StateFlow<GameSummaryUiState> = _uiState

    private var orchestrator: GameSummaryOrchestrator? = null
    private var generationJob: Job? = null

    fun attachOrchestrator(orchestrator: GameSummaryOrchestrator?) {
        this.orchestrator = orchestrator
    }

    fun hide() {
        generationJob?.cancel()
        _uiState.value = GameSummaryUiState.Hidden
    }

    fun triggerSummary(pgn: String) {
        val orchestrator = this.orchestrator ?: return
        generationJob?.cancel()

        _uiState.value = GameSummaryUiState.Loading
        generationJob = scope.launch {
            try {
                val request = GameSummaryRequest(pgn)
                orchestrator.summarizeGameStreaming(request).collect { event ->
                    when (event) {
                        is GameSummaryEvent.Streaming ->
                            _uiState.value = GameSummaryUiState.Streaming(event.partialText)
                        is GameSummaryEvent.Complete -> when (val result = event.result) {
                            is GameSummaryResult.Success ->
                                _uiState.value = GameSummaryUiState.Ready(result.explanation)
                            is GameSummaryResult.FellBack ->
                                _uiState.value = GameSummaryUiState.Fallback(result.text, result.reason)
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
