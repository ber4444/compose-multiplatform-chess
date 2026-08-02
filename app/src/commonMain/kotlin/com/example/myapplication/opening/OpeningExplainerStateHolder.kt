package com.example.myapplication.opening

import com.example.coachapi.OpeningExplainRequest
import com.example.myapplication.FenConverter
import com.example.myapplication.GameUiState
import com.example.myapplication.WinState
import com.example.ondeviceai.OpeningExplainer
import com.example.ondeviceai.OpeningExplainerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface OpeningExplainerUiState {
    data object Hidden : OpeningExplainerUiState
    data object Loading : OpeningExplainerUiState

    data class Ready(
        val text: String,
        val sourceTitles: List<String>,
        val isFallback: Boolean,
    ) : OpeningExplainerUiState
}

class OpeningExplainerStateHolder(
    private val explainer: OpeningExplainer,
) {
    private val mutableState = MutableStateFlow<OpeningExplainerUiState>(OpeningExplainerUiState.Hidden)
    val state: StateFlow<OpeningExplainerUiState> = mutableState.asStateFlow()

    suspend fun explain(gameState: GameUiState) {
        if (gameState.winState == WinState.NONE) {
            reset()
            return
        }
        mutableState.value = OpeningExplainerUiState.Loading
        val result = explainer.explain(
            OpeningExplainRequest(
                fen = FenConverter.gameStateToFen(gameState),
                // Opening retrieval must use the opening prefix. Sending the end of a completed
                // game made the service search for endgame SAN in an opening-only corpus.
                movesSan = gameState.moveHistory.take(OPENING_PLY_LIMIT).map { it.san },
                eco = null,
                locale = null,
            ),
        )
        mutableState.value = when (result) {
            is OpeningExplainerResult.Success -> result.response.toUiState(isFallback = false)
            is OpeningExplainerResult.Fallback -> result.response.toUiState(isFallback = true)
        }
    }

    fun reset() {
        mutableState.value = OpeningExplainerUiState.Hidden
    }

    fun close() {
        explainer.close()
    }

    private fun com.example.coachapi.OpeningExplainResponse.toUiState(isFallback: Boolean) =
        OpeningExplainerUiState.Ready(
            text = com.example.myapplication.ui.CitationSanitizer.sanitize(text),
            sourceTitles = passages.map { it.title }.distinct(),
            isFallback = isFallback,
        )

    companion object {
        const val OPENING_PLY_LIMIT = 20
    }
}
