package com.example.myapplication.monetization

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * No-op entitlements implementation. Defaults to locked ([initialUnlocked] = false) so that unconfigured
 * platforms fail locked by default (§0.4 review requirement).
 *
 * Desktop and Web/Wasm entry points explicitly instantiate [NoOpEntitlements](initialUnlocked = true)
 * because those targets have no native app store and keep all features unlocked.
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
