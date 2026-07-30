package com.example.ondeviceai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android `resolveVendorRoute` guards.
 *
 * `VendorRoute.FirebaseHybrid` is a **cloud-capable** vendor, but it is returned as a
 * `VendorRoute`, which `AiRoutePolicyDecider` wraps in `Decision.Route` — a decision every caller
 * treats as "handled on-device". Selecting it therefore opens a second cloud path that bypasses
 * `:server` and its validator/cost accounting, so it must only ever be chosen for a policy that
 * actually permits cloud.
 */
class VendorRouteResolverTest {

    private fun context(
        isDeviceModelAvailable: Boolean = true,
        userSetting: AiUserSetting = AiUserSetting.ALLOW_CLOUD,
    ) = AiContextSnapshot(
        isDeviceModelAvailable = isDeviceModelAvailable,
        isNetworkAvailable = true,
        isAppForegrounded = true,
        userSetting = userSetting,
    )

    @Test
    fun `no device model means no vendor route`() {
        assertNull(
            resolveVendorRoute(AiRoutePolicies.moveCoachOffline, context(isDeviceModelAvailable = false)),
        )
    }

    @Test
    fun `LOCAL_ONLY policy never gets a cloud-capable vendor`() {
        val route = resolveVendorRoute(AiRoutePolicies.moveCoachOffline, context())
        assertIs<VendorRoute.MlKitPrompt>(route)
    }

    @Test
    fun `rules qa never gets a cloud-capable vendor`() {
        val route = resolveVendorRoute(AiRoutePolicies.rulesQaOffline, context())
        assertIs<VendorRoute.MlKitPrompt>(route)
    }

    @Test
    fun `a policy that forbids cloud never gets Firebase even when not offline-only`() {
        // The hole this guard closes: allowCloud=false is neither LOCAL_ONLY nor requireOffline,
        // so `effectiveOfflineOnly` is false and the resolver previously returned FirebaseHybrid.
        val noCloud = AiRoutePolicies.positionChat.copy(allowCloud = false)
        assertTrue(!noCloud.permitsCloud())

        val route = resolveVendorRoute(noCloud, context())
        assertIs<VendorRoute.MlKitPrompt>(route)
    }

    @Test
    fun `a zero-budget policy never gets Firebase`() {
        val noBudget = AiRoutePolicies.positionChat.copy(costBudget = CostBudget(maxUsdCents = 0.0))
        val route = resolveVendorRoute(noBudget, context())
        assertIs<VendorRoute.MlKitPrompt>(route)
    }

    @Test
    fun `user OFFLINE_ONLY overrides a cloud-allowed policy`() {
        val route = resolveVendorRoute(
            AiRoutePolicies.positionChat,
            context(userSetting = AiUserSetting.OFFLINE_ONLY),
        )
        assertIs<VendorRoute.MlKitPrompt>(route)
    }

    @Test
    fun `a genuinely cloud-allowed policy still gets Firebase`() {
        // The guard must not neuter the vendor abstraction it protects.
        val route = resolveVendorRoute(AiRoutePolicies.positionChat, context())
        assertIs<VendorRoute.FirebaseHybrid>(route)
        assertEquals("HYBRID", route.mode)
    }
}
