package com.example.myapplication.monetization

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The default [Entitlements] on a platform that *has* a store but hasn't been wired to one yet
 * (Android and iOS, until the RevenueCat SDK lands — §0.4).
 *
 * Locked, and [purchasePro] **fails** rather than succeeding. That difference from
 * [NoOpEntitlements] is the whole point: a no-op that flips the flag and returns `true` would hand
 * out Pro for a tap the moment a paywall button is wired to it, on exactly the two platforms where
 * money is supposed to change hands. Failing here makes the missing wiring visible instead.
 *
 * Desktop and Web/Wasm use [NoOpEntitlements] with `initialUnlocked = true` — they have no store,
 * so everything is legitimately free there.
 */
class UnconfiguredEntitlements : Entitlements {
    private val _isProUnlocked = MutableStateFlow(false)
    override val isProUnlocked: StateFlow<Boolean> = _isProUnlocked.asStateFlow()

    /** Always `false` — there is no billing client to run a purchase through yet. */
    override suspend fun purchasePro(): Boolean = false

    /** Always `false` — nothing to restore without a billing client. */
    override suspend fun restorePurchases(): Boolean = false
}
