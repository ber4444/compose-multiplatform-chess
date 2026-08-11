package com.example.myapplication.movecoach

import androidx.compose.runtime.Immutable
import com.example.ondeviceai.MoveCoachExplanation

/**
 * UI state for the Move Coach panel (plan §8.1). Driven by collecting
 * [com.example.ondeviceai.MoveCoachEvent] from the orchestrator.
 *
 * [Hidden] is the default; the panel mounts when a coached move arrives.
 * [Unavailable] is shown when the orchestrator itself isn't injected (desktop/wasm).
 * [Loading] / [Streaming] are shown during on-device inference.
 * [Ready] / [Fallback] / [Error] are the terminal states the user reads.
 */
@Immutable
sealed interface MoveCoachUiState {
    data object Hidden : MoveCoachUiState

    /** Coach is unavailable on this device/configuration. [reason] carries an
     *  actionable hint (e.g. "Enable Apple Intelligence in Settings" or
     *  "Drop a Gemma .litertlm in app/src/androidMain/assets/models/"). */
    data class Unavailable(val reason: String? = null) : MoveCoachUiState

    /** The local model is being prepared (unpacked from assets, initialized, etc).
     *  Shown by platform glue BEFORE the orchestrator is attached so the user can
     *  distinguish "warming up" from "genuinely missing" (the [Unavailable] state). */
    data class LoadingModel(val message: String, val progress: Float? = null) : MoveCoachUiState

    data class Loading(val headline: String, val explanation: String) : MoveCoachUiState
    data class Streaming(val headline: String, val explanation: String, val text: String) : MoveCoachUiState
    /** Provenance (B11) is read off [MoveCoachExplanation.route] — the state does not copy it, so
     *  the badge can't disagree with what the orchestrator recorded. */
    data class Ready(val explanation: MoveCoachExplanation) : MoveCoachUiState
    /** [reason] stays typed so the panel can pick a designed state via [FallbackPresentation];
     *  flattening it to a string here is what made every fallback render identically (B17). It is
     *  also the whole provenance of this state: the text is engine-derived by construction. */
    data class Fallback(
        val text: String,
        val reason: com.example.ondeviceai.AiRoutePolicyDecider.FallbackReason,
    ) : MoveCoachUiState
    data class Error(val message: String) : MoveCoachUiState
}

/**
 * The coach's own prose for this state, or `null` for the states whose text is app chrome.
 *
 * Board highlighting reads squares out of this, so it deliberately excludes [MoveCoachUiState.Error],
 * [MoveCoachUiState.Unavailable] and [MoveCoachUiState.LoadingModel]: those carry diagnostics and
 * progress messages, and tinting the board off a stack trace that happens to contain "e4" would be
 * worse than not tinting at all.
 */
val MoveCoachUiState.narratedText: String?
    get() = when (this) {
        // The headline is included because it is the deterministic half of the line: Explain mode's
        // subject square is always in it, while the body is a model rewrite that may paraphrase the
        // square away entirely ("an empty central square"), leaving the tapped square untinted.
        is MoveCoachUiState.Ready -> "${explanation.headline} ${explanation.explanation}"
        is MoveCoachUiState.Fallback -> text
        is MoveCoachUiState.Streaming -> "$headline ${text.ifBlank { explanation }}"
        is MoveCoachUiState.Loading -> "$headline $explanation"
        else -> null
    }
