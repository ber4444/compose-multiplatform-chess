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
 * Desktop and Web/Wasm use [NoOpEntitlements] instead: also locked at first launch, so the paywall
 * renders there too, but its [NoOpEntitlements.purchase] grants Pro locally and free — correct on a
 * platform with no store, and a bypass on one that has it.
 *
 * Reached on Android/iOS when no RevenueCat key is configured, which is the state of any fresh
 * clone: that build genuinely cannot sell anything, so the five Pro AI surfaces are gated off.
 * A *debug* build keeps this class — its dev unlock is [LocalProUnlockOverride], which opens all
 * five surfaces without touching the entitlement, so the paywall stays inspectable.
 */
class UnconfiguredEntitlements : Entitlements {
    private val _isProUnlocked = MutableStateFlow(false)
    override val isProUnlocked: StateFlow<Boolean> = _isProUnlocked.asStateFlow()

    /** Empty — there is no billing client to enumerate offerings with. */
    override suspend fun availablePlans(): List<ProPlan> = emptyList()

    /** Always [PurchaseOutcome.Unavailable] — no billing client to run a purchase through. */
    override suspend fun purchase(planId: String): PurchaseOutcome = PurchaseOutcome.Unavailable

    /**
     * Always [RestoreOutcome.Unavailable] — not [RestoreOutcome.NothingToRestore]. There is no
     * billing client to ask, so "you have no purchase" would be a claim this class cannot make.
     */
    override suspend fun restorePurchases(): RestoreOutcome = RestoreOutcome.Unavailable
}
