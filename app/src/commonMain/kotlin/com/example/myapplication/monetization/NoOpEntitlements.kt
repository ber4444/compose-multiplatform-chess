package com.example.myapplication.monetization

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [Entitlements] for the **storeless** targets: desktop and Web/Wasm entry points construct this
 * with `initialUnlocked = true`, because there is no store to buy from and everything is free there.
 *
 * Deliberately *not* the default on Android/iOS — [purchasePro] here succeeds without any billing
 * client, so using it on a store platform would grant Pro for a tap. Those targets fall to
 * [UnconfiguredEntitlements] until the RevenueCat SDK is wired (§0.4). [initialUnlocked] still
 * defaults to `false` so an accidental bare `NoOpEntitlements()` starts locked rather than open.
 */
class NoOpEntitlements(
    initialUnlocked: Boolean = false
) : Entitlements {
    private val _isProUnlocked = MutableStateFlow(initialUnlocked)
    override val isProUnlocked: StateFlow<Boolean> = _isProUnlocked.asStateFlow()

    override suspend fun purchasePro(): Boolean {
        _isProUnlocked.value = true
        return true
    }

    override suspend fun restorePurchases(): Boolean {
        _isProUnlocked.value = true
        return true
    }
}
