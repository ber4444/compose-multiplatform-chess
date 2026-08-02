package com.example.myapplication.persistence

import kotlinx.coroutines.flow.StateFlow

/**
 * Injected monetization seam for feature entitlement gating (§0.4).
 *
 * Tier Structure:
 * - Free: Unlimited play, 2D + 3D board, Stockfish difficulty, deterministic move coach, PGN export, game history.
 * - Pro (Chess Coach Pro): Model-phrased move coach, Game Summary, Position Chat, Opening Explainer, Rules Q&A.
 *
 * Placed strictly in `:app` (never in `:chess-core`) to avoid introducing billing SDK dependencies
 * into the shared core compiled by the React Native consumer.
 */
interface Entitlements {
    /**
     * Flow emitting whether the user has access to Pro features.
     */
    val isProUnlocked: StateFlow<Boolean>

    /**
     * Launch purchase flow for Chess Coach Pro.
     */
    suspend fun purchasePro(): Boolean

    /**
     * Restore previous purchases.
     */
    suspend fun restorePurchases(): Boolean
}
