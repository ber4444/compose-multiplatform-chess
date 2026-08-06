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
    data class LoadingModel(val message: String) : MoveCoachUiState

    data class Loading(val move: String) : MoveCoachUiState
    data class Streaming(val move: String, val text: String) : MoveCoachUiState
    data class Ready(val explanation: MoveCoachExplanation, val route: com.example.ondeviceai.AiRoute) : MoveCoachUiState
    /** [reason] stays typed so the panel can pick a designed state via [FallbackPresentation];
     *  flattening it to a string here is what made every fallback render identically (B17). */
    data class Fallback(
        val text: String,
        val reason: com.example.ondeviceai.AiRoutePolicyDecider.FallbackReason,
        val route: com.example.ondeviceai.AiRoute,
    ) : MoveCoachUiState
    data class Error(val message: String) : MoveCoachUiState
}
