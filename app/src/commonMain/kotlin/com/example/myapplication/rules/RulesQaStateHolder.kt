package com.example.myapplication.rules

import com.example.ondeviceai.DefaultRulesQaOrchestrator
import com.example.ondeviceai.RulesQaResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException

sealed interface RulesQaUiState {
    data object Idle : RulesQaUiState
    data object Loading : RulesQaUiState
    data class Ready(val text: String, val passageIds: List<String>, val isFallback: Boolean) : RulesQaUiState
    data object Unavailable : RulesQaUiState
}

class RulesQaStateHolder(
    private val orchestrator: DefaultRulesQaOrchestrator?,
) {
    private val mutableState = MutableStateFlow<RulesQaUiState>(
        if (orchestrator == null) RulesQaUiState.Unavailable else RulesQaUiState.Idle,
    )
    val state: StateFlow<RulesQaUiState> = mutableState.asStateFlow()

    suspend fun ask(question: String) {
        val available = orchestrator ?: run {
            mutableState.value = RulesQaUiState.Unavailable
            return
        }
        if (question.isBlank()) return
        mutableState.value = RulesQaUiState.Loading
        try {
            mutableState.value = when (val result = available.answer(question)) {
                is RulesQaResult.Success -> RulesQaUiState.Ready(result.text, result.passageIds, isFallback = false)
                is RulesQaResult.FellBack -> RulesQaUiState.Ready(result.text, emptyList(), isFallback = true)
            }
        } catch (cancellation: CancellationException) {
            mutableState.value = RulesQaUiState.Idle
            throw cancellation
        }
    }
}
