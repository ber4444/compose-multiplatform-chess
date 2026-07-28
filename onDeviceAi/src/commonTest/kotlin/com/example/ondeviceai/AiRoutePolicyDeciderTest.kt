package com.example.ondeviceai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AiRoutePolicyDeciderTest {

    @Test
    fun `rules qa policy is local only and free`() {
        val policy = AiRoutePolicies.rulesQaOffline

        assertEquals(PrivacyClass.LOCAL_ONLY, policy.privacyClass)
        assertTrue(policy.requireOffline)
        assertFalse(policy.allowCloud)
        assertEquals(0.0, policy.costBudget.maxUsdCents)
    }

    @Test
    fun `rules qa can never route to cloud`() {
        val contexts = listOf(
            context(isDeviceModelAvailable = false, isNetworkAvailable = true),
            context(isDeviceModelAvailable = false, isNetworkAvailable = false),
            context(isDeviceModelAvailable = true, isNetworkAvailable = true),
            context(
                isDeviceModelAvailable = false,
                isNetworkAvailable = true,
                thermalState = ThermalState.CRITICAL,
            ),
        )

        contexts.forEach { snapshot ->
            assertFalse(
                AiRoutePolicyDecider.decide(AiRoutePolicies.rulesQaOffline, snapshot) is
                    AiRoutePolicyDecider.Decision.RunCloud,
            )
        }
    }

    private fun context(
        isDeviceModelAvailable: Boolean,
        isNetworkAvailable: Boolean,
        thermalState: ThermalState = ThermalState.NOMINAL,
    ) = AiContextSnapshot(
        isDeviceModelAvailable = isDeviceModelAvailable,
        isNetworkAvailable = isNetworkAvailable,
        isAppForegrounded = true,
        userSetting = AiUserSetting.ALLOW_CLOUD,
        thermalState = thermalState,
    )

    @Test
    fun `opening explainer policy is public cloud-capable and budgeted`() {
        assertEquals(PrivacyClass.PUBLIC_OR_SYNTHETIC, AiRoutePolicies.openingExplainer.privacyClass)
        assertEquals(true, AiRoutePolicies.openingExplainer.allowCloud)
        assertEquals(false, AiRoutePolicies.openingExplainer.requireOffline)
        assertEquals(2500, AiRoutePolicies.openingExplainer.latencyBudget.firstTokenMs)
        assertEquals(8000, AiRoutePolicies.openingExplainer.latencyBudget.completeMs)
        assertEquals(0.2, AiRoutePolicies.openingExplainer.costBudget.maxUsdCents)
    }

    @Test
    fun `move coach can never route to cloud across runtime contexts`() {
        val contexts = buildList {
            for (hasModel in listOf(false, true)) {
                for (hasNetwork in listOf(false, true)) {
                    for (setting in AiUserSetting.entries) {
                        for (thermal in ThermalState.entries) {
                            add(
                                AiContextSnapshot(
                                    isDeviceModelAvailable = hasModel,
                                    isNetworkAvailable = hasNetwork,
                                    isAppForegrounded = true,
                                    userSetting = setting,
                                    thermalState = thermal,
                                ),
                            )
                        }
                    }
                }
            }
        }

        contexts.forEach { context ->
            val decision = AiRoutePolicyDecider.decide(AiRoutePolicies.moveCoachOffline, context)
            kotlin.test.assertNotEquals(AiRoutePolicyDecider.Decision.RunCloud, decision)
        }
    }

    @Test
    fun `opening explainer without a local model routes to cloud when configured`() {
        val decision = AiRoutePolicyDecider.decide(
            AiRoutePolicies.openingExplainer,
            AiContextSnapshot(
                isDeviceModelAvailable = false,
                isNetworkAvailable = true,
                userSetting = AiUserSetting.ALLOW_CLOUD,
            ),
        )

        assertEquals(AiRoutePolicyDecider.Decision.RunCloud, decision)
    }

    private val moveCoachContext = AiContextSnapshot(
        isDeviceModelAvailable = true,
        isAppForegrounded = true,
        userSetting = AiUserSetting.OFFLINE_ONLY,
    )

    @Test
    fun `LOCAL_ONLY with available model runs on-device`() {
        val decision = AiRoutePolicyDecider.decide(
            AiRoutePolicies.moveCoachOffline,
            moveCoachContext,
        )
        assertIs<AiRoutePolicyDecider.Decision.Route>(decision)
    }

    @Test
    fun `LOCAL_ONLY without model falls back even when cloud is configured`() {
        val cloudCapable = AiContextSnapshot(
            isDeviceModelAvailable = false,
            isNetworkAvailable = true,
            isAppForegrounded = true,
            userSetting = AiUserSetting.ALLOW_CLOUD,
        )
        val decision = AiRoutePolicyDecider.decide(
            AiRoutePolicies.moveCoachOffline,
            cloudCapable,
        )
        assertIs<AiRoutePolicyDecider.Decision.FallBack>(decision)
        assertEquals(AiRoutePolicyDecider.FALLBACK_NO_LOCAL_MODEL, decision.reason)
    }

    @Test
    fun `LOCAL_ONLY falls back when app is backgrounded regardless of model`() {
        val backgrounded = moveCoachContext.copy(isAppForegrounded = false)
        val decision = AiRoutePolicyDecider.decide(
            AiRoutePolicies.moveCoachOffline,
            backgrounded,
        )
        assertIs<AiRoutePolicyDecider.Decision.FallBack>(decision)
        assertEquals(AiRoutePolicyDecider.FALLBACK_BACKGROUND, decision.reason)
    }

    @Test
    fun `USER_PRIVATE with no local model and network takes cloud when allowed`() {
        val policy = AiRoutePolicy(
            privacyClass = PrivacyClass.USER_PRIVATE,
            latencyBudget = LatencyBudget(500, 2000),
            costBudget = CostBudget(maxUsdCents = 5.0),
            allowCloud = true,
            requireOffline = false,
        )
        val context = AiContextSnapshot(
            isDeviceModelAvailable = false,
            isNetworkAvailable = true,
            isAppForegrounded = true,
            userSetting = AiUserSetting.ALLOW_CLOUD,
        )
        val decision = AiRoutePolicyDecider.decide(policy, context)
        assertEquals(AiRoutePolicyDecider.Decision.RunCloud, decision)
    }

    @Test
    fun `USER_PRIVATE with model present still prefers on-device`() {
        val policy = AiRoutePolicy(
            privacyClass = PrivacyClass.USER_PRIVATE,
            latencyBudget = LatencyBudget(500, 2000),
            costBudget = CostBudget(maxUsdCents = 5.0),
            allowCloud = true,
            requireOffline = false,
        )
        val context = AiContextSnapshot(
            isDeviceModelAvailable = true,
            isNetworkAvailable = true,
            isAppForegrounded = true,
            userSetting = AiUserSetting.ALLOW_CLOUD,
        )
        assertIs<AiRoutePolicyDecider.Decision.Route>(
            AiRoutePolicyDecider.decide(policy, context),
        )
    }

    @Test
    fun `PUBLIC_OR_SYNTHETIC with OFFLINE_ONLY user setting forces fallback when no model`() {
        val policy = AiRoutePolicy(
            privacyClass = PrivacyClass.PUBLIC_OR_SYNTHETIC,
            latencyBudget = LatencyBudget(500, 2000),
            costBudget = CostBudget(maxUsdCents = 5.0),
            allowCloud = true,
            requireOffline = false,
        )
        val context = AiContextSnapshot(
            isDeviceModelAvailable = false,
            isNetworkAvailable = true,
            isAppForegrounded = true,
            userSetting = AiUserSetting.OFFLINE_ONLY,
        )
        val decision = AiRoutePolicyDecider.decide(policy, context)
        assertIs<AiRoutePolicyDecider.Decision.FallBack>(decision)
        assertEquals(AiRoutePolicyDecider.FALLBACK_NO_LOCAL_MODEL, decision.reason)
    }

    @Test
    fun `PREFER_LOCAL setting without local model falls back when policy disallows cloud`() {
        val policy = AiRoutePolicy(
            privacyClass = PrivacyClass.PUBLIC_OR_SYNTHETIC,
            latencyBudget = LatencyBudget(500, 2000),
            costBudget = CostBudget(maxUsdCents = 5.0),
            allowCloud = false,
            requireOffline = false,
        )
        val context = AiContextSnapshot(
            isDeviceModelAvailable = false,
            isNetworkAvailable = true,
            isAppForegrounded = true,
            userSetting = AiUserSetting.PREFER_LOCAL,
        )
        val decision = AiRoutePolicyDecider.decide(policy, context)
        assertIs<AiRoutePolicyDecider.Decision.FallBack>(decision)
    }

    @Test
    fun `cloud-allowed request with no network falls back`() {
        val policy = AiRoutePolicy(
            privacyClass = PrivacyClass.PUBLIC_OR_SYNTHETIC,
            latencyBudget = LatencyBudget(500, 2000),
            costBudget = CostBudget(maxUsdCents = 5.0),
            allowCloud = true,
            requireOffline = false,
        )
        val context = AiContextSnapshot(
            isDeviceModelAvailable = false,
            isNetworkAvailable = false,
            isAppForegrounded = true,
            userSetting = AiUserSetting.ALLOW_CLOUD,
        )
        val decision = AiRoutePolicyDecider.decide(policy, context)
        assertIs<AiRoutePolicyDecider.Decision.FallBack>(decision)
        assertEquals(AiRoutePolicyDecider.FALLBACK_NO_NETWORK, decision.reason)
    }

    @Test
    fun `maxUsdCents=0 with cloud allowed stays on-device when model available`() {
        val policy = AiRoutePolicy(
            privacyClass = PrivacyClass.PUBLIC_OR_SYNTHETIC,
            latencyBudget = LatencyBudget(500, 2000),
            costBudget = CostBudget(maxUsdCents = 0.0),
            allowCloud = true,
            requireOffline = false,
        )
        val context = AiContextSnapshot(
            isDeviceModelAvailable = true,
            isNetworkAvailable = true,
            isAppForegrounded = true,
            userSetting = AiUserSetting.ALLOW_CLOUD,
        )
        assertIs<AiRoutePolicyDecider.Decision.Route>(
            AiRoutePolicyDecider.decide(policy, context),
        )
    }

    @Test
    fun `CRITICAL thermal state falls back when no cloud`() {
        val policy = AiRoutePolicies.moveCoachOffline
        val context = moveCoachContext.copy(thermalState = ThermalState.CRITICAL)
        val decision = AiRoutePolicyDecider.decide(policy, context)
        assertIs<AiRoutePolicyDecider.Decision.FallBack>(decision)
        assertEquals(AiRoutePolicyDecider.FALLBACK_THERMAL, decision.reason)
    }

    @Test
    fun `CRITICAL thermal state with cloud-allowed policy routes to cloud`() {
        val policy = AiRoutePolicy(
            privacyClass = PrivacyClass.PUBLIC_OR_SYNTHETIC,
            latencyBudget = LatencyBudget(500, 2000),
            costBudget = CostBudget(maxUsdCents = 5.0),
            allowCloud = true,
            requireOffline = false,
        )
        val context = AiContextSnapshot(
            isDeviceModelAvailable = true,
            isNetworkAvailable = true,
            isAppForegrounded = true,
            userSetting = AiUserSetting.ALLOW_CLOUD,
            thermalState = ThermalState.CRITICAL,
        )
        assertEquals(
            AiRoutePolicyDecider.Decision.RunCloud,
            AiRoutePolicyDecider.decide(policy, context),
        )
    }
}
