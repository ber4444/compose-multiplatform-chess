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
        assertEquals(false, entitlements.restorePurchases())
        // Still locked after a failed attempt — the failure must not be a backdoor.
        assertEquals(false, entitlements.isProUnlocked.value)
    }

    @Test
    fun `a bare NoOpEntitlements starts locked`() {
        // The storeless targets pass initialUnlocked = true explicitly; an accidental bare
        // constructor must not silently open everything.
        assertEquals(false, NoOpEntitlements().isProUnlocked.value)
    }

    @Test
    fun `storeless entitlements are unlocked and have nothing to sell`() = runTest {
        val entitlements = NoOpEntitlements(initialUnlocked = true)
        assertEquals(true, entitlements.isProUnlocked.value)
        // Empty plans is what drives the paywall's "not available here" state instead of a dead
        // purchase button.
        assertTrue(entitlements.availablePlans().isEmpty())
    }

    @Test
    fun `neither SDK-free implementation offers plans`() = runTest {
        assertTrue(UnconfiguredEntitlements().availablePlans().isEmpty())
        assertTrue(NoOpEntitlements(initialUnlocked = true).availablePlans().isEmpty())
    }
}
