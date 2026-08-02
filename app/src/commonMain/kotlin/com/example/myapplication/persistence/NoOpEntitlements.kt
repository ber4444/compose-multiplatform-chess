package com.example.myapplication.persistence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * No-op entitlements implementation for platforms without an app store (Desktop & Web/Wasm),
 * or for development/testing where all features are unlocked.
 */
class NoOpEntitlements(
    initialUnlocked: Boolean = true
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
