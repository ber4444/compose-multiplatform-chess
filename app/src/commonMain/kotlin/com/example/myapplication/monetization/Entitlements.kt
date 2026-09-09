package com.example.myapplication.monetization

import kotlinx.coroutines.flow.StateFlow

/**
 * A purchasable plan, in store-agnostic terms. Deliberately **not** a RevenueCat type: this crosses
 * into `commonMain` and the paywall UI, and `purchases-kmp` only exists on Android and iOS
 * (see the `storeMain` source set), so a leaked SDK type would break desktop and wasm.
 *
 * [priceLabel] is the store's own localized, currency-formatted string — never format prices
 * yourself from an amount, and never assume a currency symbol position.
 */
data class ProPlan(
    /** Opaque package identifier; hand it straight back to [Entitlements.purchase]. */
    val id: String,
    val title: String,
    val priceLabel: String,
    /** Store-provided extra, e.g. an introductory-offer line. Null when there isn't one. */
    val detail: String? = null,
    /** Set on at most one plan, to mark the plan the paywall should emphasize. */
    val isBestValue: Boolean = false,
)

/**
 * Outcome of a purchase attempt.
 *
 * [Cancelled] exists so the paywall can stay silent when the user backs out of the store sheet.
 * Collapsing it into [Failed] produces the most common paywall bug there is: an error toast for a
 * deliberate, correct user action.
 */
sealed interface PurchaseOutcome {
    data object Purchased : PurchaseOutcome
    data object Cancelled : PurchaseOutcome
    /** No store, no configured SDK, or no offering — nothing the user can do about it. */
    data object Unavailable : PurchaseOutcome
    data class Failed(val message: String?) : PurchaseOutcome
}

/**
 * Outcome of a restore attempt.
 *
 * [NothingToRestore] and [Failed] are distinct because collapsing them into one `false` is what made
 * a restore that *could never work* look like a broken one: a network error, an unconfigured key and
 * a genuinely empty store account all rendered as "No previous purchase found." The user then has no
 * way to tell "you never bought this" from "we couldn't ask".
 */
sealed interface RestoreOutcome {
    /** The store account owns the entitlement; Pro is now unlocked. */
    data object Restored : RestoreOutcome
    /** The store answered, and this account has no purchase to restore. */
    data object NothingToRestore : RestoreOutcome
    /** No store or no billing client — the same "nothing we can do" as [PurchaseOutcome.Unavailable]. */
    data object Unavailable : RestoreOutcome
    /** The store was asked and errored. **Not** a signal to revoke an existing unlock. */
    data class Failed(val message: String?) : RestoreOutcome
}

/**
 * Injected monetization seam for feature entitlement gating (§0.4).
 *
 * Tier structure:
 * - **Free** — unlimited play, 2D + 3D board, full Stockfish difficulty range, the **deterministic**
 *   move coach, PGN export, game history.
 * - **Pro** — the model-phrased move coach, Game Summary, Position Chat, Opening Explainer,
 *   Rules Q&A.
 *
 * Lives strictly in `:app`, never `:chess-core`, so no billing dependency reaches the artifact the
 * React Native consumer compiles against.
 */
interface Entitlements {
    /** Whether the user currently has Pro. */
    val isProUnlocked: StateFlow<Boolean>

    /**
     * Plans to show on the paywall, most-prominent first. Empty means "nothing to sell right now"
     * — no store, no configured key, no current offering, or offline — and the paywall should say
     * so rather than render a dead purchase button.
     */
    suspend fun availablePlans(): List<ProPlan>

    /** Launch the store purchase flow for [planId], which must come from [availablePlans]. */
    suspend fun purchase(planId: String): PurchaseOutcome

    /**
     * Restore a previous purchase from the underlying **store account**.
     *
     * That is the whole mechanism: the SDK re-reads the App Store / Play account's transactions and
     * syncs them onto the current App User ID. Nothing here can recover a purchase the store has no
     * record of — see the Test Store note in `RevenueCatEntitlements`.
     */
    suspend fun restorePurchases(): RestoreOutcome
}
