package com.example.myapplication.monetization

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [Entitlements] for the **storeless** targets: desktop and Web/Wasm. `purchases-kmp-core` has no
 * JVM or wasm variant (see the `storeMain` source set), so there is no billing client to talk to
 * and nothing to charge through.
 *
 * Those targets nonetheless start **locked**, so the paywall and every `ProGate` upsell render and
 * stay testable at desktop and browser window sizes — the two form factors no phone build covers.
 * [purchase] then unlocks locally and for free, which is the honest outcome where there is no store:
 * the user is not being denied something they could otherwise have paid for.
 *
 * Deliberately *not* the default on Android/iOS — a free unlock there would hand out Pro for a tap
 * on exactly the two platforms where money is meant to change hands. Those use
 * [RevenueCatEntitlements], falling back to [UnconfiguredEntitlements] when no API key is
 * configured (§0.4). The one exception is an unkeyed **debug** build, which takes this class with
 * `initialUnlocked = true` so the Pro surfaces are reachable for development; the entry points gate
 * that on `FLAG_DEBUGGABLE` / `Platform.isDebugBinary`, so release never reaches it.
 * [initialUnlocked] still defaults to `false` so an accidental bare `NoOpEntitlements()` starts
 * locked rather than open.
 *
 * @param onUnlockChanged persistence hook — desktop and wasm pass `AppSettings::setProUnlocked` so
 *   the unlock survives a restart. Without it every launch would re-show the paywall, which reads
 *   as a bug rather than as a tier.
 */
class NoOpEntitlements(
    initialUnlocked: Boolean = false,
    private val onUnlockChanged: (Boolean) -> Unit = {},
) : Entitlements {
    private val _isProUnlocked = MutableStateFlow(initialUnlocked)
    override val isProUnlocked: StateFlow<Boolean> = _isProUnlocked.asStateFlow()

    /**
     * One synthetic plan, priced at "Free". Non-empty on purpose: an empty list drives the
     * paywall's "purchases aren't available" state, which would leave a storeless user permanently
     * locked out of features that cost nothing to grant.
     */
    override suspend fun availablePlans(): List<ProPlan> = listOf(
        ProPlan(
            id = LOCAL_PLAN_ID,
            title = "Pro",
            priceLabel = "Free",
            detail = "Free on this platform — there's no store to charge through.",
        ),
    )

    override suspend fun purchase(planId: String): PurchaseOutcome {
        unlock()
        return PurchaseOutcome.Purchased
    }

    override suspend fun restorePurchases(): Boolean {
        unlock()
        return true
    }

    private fun unlock() {
        _isProUnlocked.value = true
        onUnlockChanged(true)
    }

    companion object {
        /** Id of the single synthetic plan. [purchase] accepts anything; the paywall sends this. */
        const val LOCAL_PLAN_ID = "local-free-pro"
    }
}
