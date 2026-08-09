package com.example.myapplication.rules

import com.example.myapplication.ui.CitationSanitizer
import com.example.ondeviceai.DefaultRulesQaOrchestrator
import com.example.ondeviceai.RuleCitation
import com.example.ondeviceai.RulesQaResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException

sealed interface RulesQaUiState {
    data object Idle : RulesQaUiState
    data object Loading : RulesQaUiState
    data class Ready(
        val text: String,
        val sources: List<RuleCitation>,
        val fallbackReason: com.example.ondeviceai.AiRoutePolicyDecider.FallbackReason? = null,
    ) : RulesQaUiState {
        /** Ids alone, for tests and any caller keying on identity. Derived, never stored. */
        val passageIds: List<String> get() = sources.map { it.id }
    }
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
            // Sanitize here, not in the composable — same place Opening Explainer and Position Chat
            // do it. The corpus ids are what `RulesQaResponseValidator` checks for, so they must
            // survive validation upstream and be stripped only on the way to the screen; the
            // `Sources:` line is where they belong. This surface was the one display path missing
            // the call, which is how `[draw-dead-position]` ended up rendered verbatim to the user
            // even though CitationSanitizer's own doc names that exact id as what it removes.
            mutableState.value = when (val result = available.answer(question)) {
                is RulesQaResult.Success ->
                    RulesQaUiState.Ready(
                        CitationSanitizer.sanitize(result.text),
                        result.citations,
                        fallbackReason = null,
                    )
                is RulesQaResult.FellBack ->
                    RulesQaUiState.Ready(
                        CitationSanitizer.sanitize(result.text),
                        emptyList(),
                        fallbackReason = result.reason,
                    )
            }
        } catch (cancellation: CancellationException) {
            mutableState.value = RulesQaUiState.Idle
            throw cancellation
        }
    }
}
