package com.example.myapplication.monetization

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The default [Entitlements] on a platform that *has* a store but hasn't been wired to one yet
 * (Android and iOS, until the RevenueCat SDK lands — §0.4).
 *
 * Locked, and [purchase] **fails** rather than succeeding. That difference from
 * [NoOpEntitlements] is the whole point: a no-op that flips the flag and returns `true` would hand
 * out Pro for a tap the moment a paywall button is wired to it, on exactly the two platforms where
 * money is supposed to change hands. Failing here makes the missing wiring visible instead.
 *
 * Desktop and Web/Wasm use [NoOpEntitlements] with `initialUnlocked = true` — they have no store,
 * so everything is legitimately free there.
 *
 * Reached on Android/iOS when no RevenueCat key is configured, which is the state of any fresh
 * clone. **Consequence worth knowing:** the five Pro AI surfaces are gated off in that build. That
 * is correct rather than convenient — an unkeyed build genuinely cannot sell anything — but it does
 * mean a keyless dev build shows the free tier. Desktop keeps everything unlocked for development.
 */
class UnconfiguredEntitlements : Entitlements {
    private val _isProUnlocked = MutableStateFlow(false)
    override val isProUnlocked: StateFlow<Boolean> = _isProUnlocked.asStateFlow()

    /** Empty — there is no billing client to enumerate offerings with. */
    override suspend fun availablePlans(): List<ProPlan> = emptyList()

    /** Always [PurchaseOutcome.Unavailable] — no billing client to run a purchase through. */
    override suspend fun purchase(planId: String): PurchaseOutcome = PurchaseOutcome.Unavailable

    /** Always `false` — nothing to restore without a billing client. */
    override suspend fun restorePurchases(): Boolean = false
}
