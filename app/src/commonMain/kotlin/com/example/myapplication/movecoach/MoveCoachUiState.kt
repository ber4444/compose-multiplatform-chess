package com.example.myapplication.movecoach

import androidx.compose.runtime.Immutable
import com.example.myapplication.MoveClass
import com.example.myapplication.board3d.HighlightTone
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

    data class Loading(
        val headline: String,
        val explanation: String,
        override val tone: HighlightTone = HighlightTone.NEUTRAL,
        override val squares: List<String> = emptyList(),
    ) : MoveCoachUiState, Toned
    data class Streaming(
        val headline: String,
        val explanation: String,
        val text: String,
        override val tone: HighlightTone = HighlightTone.NEUTRAL,
        override val squares: List<String> = emptyList(),
    ) : MoveCoachUiState, Toned
    /** Provenance (B11) is read off [MoveCoachExplanation.route] — the state does not copy it, so
     *  the badge can't disagree with what the orchestrator recorded. */
    data class Ready(
        val explanation: MoveCoachExplanation,
        override val tone: HighlightTone = HighlightTone.NEUTRAL,
        override val squares: List<String> = emptyList(),
    ) : MoveCoachUiState, Toned
    /** [reason] stays typed so the panel can pick a designed state via [FallbackPresentation];
     *  flattening it to a string here is what made every fallback render identically (B17). It is
     *  also the whole provenance of this state: the text is engine-derived by construction. */
    data class Fallback(
        val text: String,
        val reason: com.example.ondeviceai.AiRoutePolicyDecider.FallbackReason,
        override val tone: HighlightTone = HighlightTone.NEUTRAL,
        override val squares: List<String> = emptyList(),
    ) : MoveCoachUiState, Toned
    data class Error(val message: String) : MoveCoachUiState

    /**
     * The states that can carry a verdict on the move being discussed.
     *
     * It is on the state rather than in a second `StateFlow` on the manager so the text and the
     * board colour can never disagree: they are read from one value in one composition. A parallel
     * flow would let a recomposition see the new line beside the previous move's colour.
     *
     * Every tone defaults to [HighlightTone.NEUTRAL], so a path that has no verdict to report —
     * Explain mode, a book move, any fallback — keeps the board's authored blue by doing nothing.
     */
    sealed interface Toned : MoveCoachUiState {
        val tone: HighlightTone

        /**
         * The squares this state is about, algebraic, or empty when it names none.
         *
         * Stated rather than parsed out of the prose. `squaresNamedIn(narratedText)` was the only
         * source, which quietly made the wording load-bearing: the highlight survived only as long
         * as some sentence happened to spell the move out, so editing a template for readability
         * could delete a board affordance with nothing failing. The manager knows the move's
         * from/to squares exactly — it is holding the UCI — so it says so.
         *
         * Prose parsing is still the fallback for the squares a *model* brings up unprompted.
         */
        val squares: List<String>
    }
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

/** The tone this state paints its squares in; [HighlightTone.NEUTRAL] for states with no verdict. */
val MoveCoachUiState.highlightTone: HighlightTone
    get() = (this as? MoveCoachUiState.Toned)?.tone ?: HighlightTone.NEUTRAL

/**
 * The engine's verdict on a ply, as a board colour.
 *
 * `BOOK` maps to [HighlightTone.NEUTRAL] deliberately: opening theory is not the player getting it
 * right or wrong, and painting the board green for following a book line credits them for a move
 * the book chose. A null class — no engine attached, so no assessment — is neutral for the same
 * reason: nothing was measured.
 */
fun MoveClass?.toHighlightTone(): HighlightTone = when (this) {
    MoveClass.BEST, MoveClass.EXCELLENT, MoveClass.GOOD -> HighlightTone.GOOD
    MoveClass.INACCURACY -> HighlightTone.INACCURATE
    MoveClass.MISTAKE, MoveClass.BLUNDER -> HighlightTone.BAD
    MoveClass.BOOK, null -> HighlightTone.NEUTRAL
}
