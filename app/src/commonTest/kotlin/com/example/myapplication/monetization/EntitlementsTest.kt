package com.example.myapplication.monetization

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The split between the two SDK-free implementations is the thing most likely to be "simplified"
 * into a bug: collapsing them means a paywall button grants Pro for a tap on exactly the two
 * platforms where money changes hands.
 */
class EntitlementsTest {

    @Test
    fun `unconfigured entitlements stay locked and refuse to purchase`() = runTest {
        val entitlements = UnconfiguredEntitlements()
        assertEquals(false, entitlements.isProUnlocked.value)
        assertEquals(PurchaseOutcome.Unavailable, entitlements.purchase("any"))
        // Unavailable, not NothingToRestore: with no billing client this class cannot know whether
        // the user has a purchase, and the paywall must not tell them they don't.
        assertEquals(RestoreOutcome.Unavailable, entitlements.restorePurchases())
        // Still locked after a failed attempt — the failure must not be a backdoor.
        assertEquals(false, entitlements.isProUnlocked.value)
    }

    @Test
    fun `storeless entitlements start locked so the paywall renders on desktop and wasm`() = runTest {
        val entitlements = NoOpEntitlements()
        assertEquals(false, entitlements.isProUnlocked.value)
        // Non-empty plans keep the paywall out of its "purchases aren't available" dead end, which
        // would otherwise lock a storeless user out permanently.
        assertEquals(listOf(NoOpEntitlements.LOCAL_PLAN_ID), entitlements.availablePlans().map { it.id })
        assertEquals(PurchaseOutcome.Purchased, entitlements.purchase(NoOpEntitlements.LOCAL_PLAN_ID))
        assertEquals(true, entitlements.isProUnlocked.value)
    }

    @Test
    fun `storeless unlock is reported for persistence`() = runTest {
        // Desktop/wasm pass AppSettings::setProUnlocked here; without it the paywall would reappear
        // on every launch.
        var persisted: Boolean? = null
        val entitlements = NoOpEntitlements(onUnlockChanged = { persisted = it })
        entitlements.purchase(NoOpEntitlements.LOCAL_PLAN_ID)
        assertEquals(true, persisted)

        var restorePersisted: Boolean? = null
        val restored = NoOpEntitlements(onUnlockChanged = { restorePersisted = it }).restorePurchases()
        assertEquals(RestoreOutcome.Restored, restored)
        assertEquals(true, restorePersisted)
    }

    @Test
    fun `a seeded storeless unlock survives construction`() {
        assertEquals(true, NoOpEntitlements(initialUnlocked = true).isProUnlocked.value)
    }

    @Test
    fun `the store-platform default offers no plans`() = runTest {
        // Unlike NoOpEntitlements: there is a store here, so an empty offering must render the
        // paywall's "not available" copy rather than a free unlock.
        assertTrue(UnconfiguredEntitlements().availablePlans().isEmpty())
    }
}
