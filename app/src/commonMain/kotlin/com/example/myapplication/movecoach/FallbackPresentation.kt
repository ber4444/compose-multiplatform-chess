package com.example.myapplication.movecoach

import com.example.ondeviceai.AiRoutePolicyDecider.FallbackReason

/**
 * B17: the single place a [FallbackReason] becomes a *product state*. Shared by the Move Coach
 * panel and the Game Summary block so the two surfaces can't drift.
 *
 * The taxonomy deliberately collapses to three designed states rather than one state per reason:
 *
 * - **[Silent]** — the deterministic coach *is* the product, not an error message. No-model,
 *   offline, no-route, backgrounded, a validator veto, and CRITICAL thermal all land here: the user
 *   reads a genuinely useful line and nothing tells them a model was involved. This is also the
 *   "never render a dead panel on CRITICAL thermal" guarantee — thermal degrades to text, and the
 *   text is never blank because [com.example.ondeviceai.MoveCoachFallback] always produces one.
 * - **[Labeled]** — the substitution is worth naming because the user's next action depends on it.
 * - **[Retryable]** — as [Labeled], plus the attempt is worth repeating.
 *
 * Adding a [FallbackReason] case forces a decision here (the `when` is exhaustive), which is the
 * point: B17 exists because the states were being discovered at runtime instead of chosen.
 */
sealed interface FallbackPresentation {
    data object Silent : FallbackPresentation
    data class Labeled(val label: String) : FallbackPresentation
    data class Retryable(val label: String) : FallbackPresentation

    companion object {
        /** Quota is the one reason where waiting changes the outcome, so it says so. */
        const val BUSY_LABEL = "The on-device model is busy — here's the quick tip."

        /** A timeout is transient; the same request may well succeed on a second run. */
        const val SLOW_LABEL = "That took too long — here's the quick tip."

        fun of(reason: FallbackReason): FallbackPresentation = when (reason) {
            FallbackReason.Quota -> Labeled(BUSY_LABEL)
            FallbackReason.Timeout -> Retryable(SLOW_LABEL)
            // Substitute silently when the substitute is as good.
            FallbackReason.NoLocalModel,
            FallbackReason.NoNetwork,
            FallbackReason.NoRoute,
            FallbackReason.Background,
            FallbackReason.Thermal,
            FallbackReason.Validation,
            is FallbackReason.Other,
            -> Silent
        }
    }
}
