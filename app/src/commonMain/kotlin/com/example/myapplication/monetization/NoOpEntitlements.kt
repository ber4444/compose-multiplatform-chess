package com.example.myapplication.monetization

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [Entitlements] for the **storeless** targets: desktop and Web/Wasm entry points construct this
 * with `initialUnlocked = true`, because there is no store to buy from and everything is free there.
 *
 * Deliberately *not* the default on Android/iOS — [purchase] here succeeds without any billing
 * client, so using it on a store platform would grant Pro for a tap. Those targets use
 * [RevenueCatEntitlements], falling back to [UnconfiguredEntitlements] when no API key is
 * configured (§0.4). [initialUnlocked] still defaults to `false` so an accidental bare
 * `NoOpEntitlements()` starts locked rather than open.
 */
class NoOpEntitlements(
    initialUnlocked: Boolean = false
) : Entitlements {
    private val _isProUnlocked = MutableStateFlow(initialUnlocked)
    override val isProUnlocked: StateFlow<Boolean> = _isProUnlocked.asStateFlow()

    /** Empty: nothing to sell on a storeless platform, and the paywall says so rather than
     *  rendering a dead purchase button. */
    override suspend fun availablePlans(): List<ProPlan> = emptyList()

    override suspend fun purchase(planId: String): PurchaseOutcome {
        _isProUnlocked.value = true
        return PurchaseOutcome.Purchased
    }

    override suspend fun restorePurchases(): Boolean {
        _isProUnlocked.value = true
        return true
    }
}
