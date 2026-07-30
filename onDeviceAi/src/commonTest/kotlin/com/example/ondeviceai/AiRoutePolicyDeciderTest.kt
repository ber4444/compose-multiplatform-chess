package com.example.ondeviceai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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
    fun `position chat policy is public cloud-only and budgeted for streaming`() {
        val policy = AiRoutePolicies.positionChat
        assertEquals(PrivacyClass.PUBLIC_OR_SYNTHETIC, policy.privacyClass)
        assertEquals(true, policy.allowCloud)
        assertEquals(false, policy.requireOffline)
        // First-token budget matters for the streaming UX; complete budget is generous for a full turn.
        assertEquals(2500, policy.latencyBudget.firstTokenMs)
        assertEquals(true, policy.latencyBudget.completeMs >= policy.latencyBudget.firstTokenMs)
        assertEquals(0.2, policy.costBudget.maxUsdCents)
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
    fun `position chat routes to cloud only under the allowed contexts across the 60-context sweep`() {
        // The decider grid: 2 (hasModel) × 2 (hasNetwork) × 3 (userSetting) × 5 (thermalState) = 60,
        // plus the foreground assumption. positionChat is cloud-only: it routes to Cloud exactly when
        // allowCloud(policy) && hasNetwork && !backgrounded && thermal != CRITICAL && underCostCeiling
        // AND a local model is NOT present (the decider prefers on-device when one is available).
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
            val decision = AiRoutePolicyDecider.decide(AiRoutePolicies.positionChat, context)
            // Mirrors AiRoutePolicyDecider's precedence exactly: under CRITICAL thermal the decider
            // prefers Cloud (to spare the device) whenever the policy allows it and the network is up,
            // independent of the user's OFFLINE_ONLY setting; otherwise Cloud is taken only when there
            // is no local model (chat has none), the user hasn't pinned OFFLINE_ONLY, and the network
            // is available.
            val cloudAllowedByPolicy = AiRoutePolicies.positionChat.allowCloud &&
                !AiRoutePolicies.positionChat.requireOffline &&
                AiRoutePolicies.positionChat.privacyClass != PrivacyClass.LOCAL_ONLY &&
                AiRoutePolicies.positionChat.costBudget.maxUsdCents > 0.0
            val shouldRunCloud = if (context.thermalState == ThermalState.CRITICAL) {
                cloudAllowedByPolicy && context.isNetworkAvailable
            } else {
                !context.isDeviceModelAvailable &&
                    context.userSetting != AiUserSetting.OFFLINE_ONLY &&
                    cloudAllowedByPolicy &&
                    context.isNetworkAvailable
            }
            if (shouldRunCloud) {
                assertEquals(
                    AiRoutePolicyDecider.Decision.RunCloud,
                    decision,
                    "expected Cloud for $context but got $decision",
                )
            } else {
                assertNotEquals(
                    AiRoutePolicyDecider.Decision.RunCloud,
                    decision,
                    "expected non-Cloud for $context but got $decision",
                )
            }
        }
    }

    @Test
    fun `position chat falls back when the app is backgrounded`() {
        val decision = AiRoutePolicyDecider.decide(
            AiRoutePolicies.positionChat,
            AiContextSnapshot(
                isDeviceModelAvailable = false,
                isNetworkAvailable = true,
                isAppForegrounded = false,
                userSetting = AiUserSetting.ALLOW_CLOUD,
            ),
        )
        assertIs<AiRoutePolicyDecider.Decision.FallBack>(decision)
        assertEquals(AiRoutePolicyDecider.FALLBACK_BACKGROUND, decision.reason)
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
        assertEquals(AiRoutePolicyDecider.Decision.RunOnDevice, decision)
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
        assertEquals(
            AiRoutePolicyDecider.Decision.RunOnDevice,
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
        assertEquals(
            AiRoutePolicyDecider.Decision.RunOnDevice,
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

    // --- Cloud-permission predicate + the mechanism the cloud surfaces depend on ---------------

    @Test
    fun `permitsCloud is false unless every condition holds`() {
        // allowCloud is not implied by the others: a policy can be non-LOCAL_ONLY and
        // non-requireOffline yet still forbid cloud. Such a policy must never get a cloud vendor.
        val cloudCapableButNotAllowed = AiRoutePolicies.positionChat.copy(allowCloud = false)
        assertFalse(cloudCapableButNotAllowed.permitsCloud())

        assertFalse(AiRoutePolicies.positionChat.copy(requireOffline = true).permitsCloud())
        assertFalse(
            AiRoutePolicies.positionChat.copy(privacyClass = PrivacyClass.LOCAL_ONLY).permitsCloud(),
        )
        assertFalse(
            AiRoutePolicies.positionChat.copy(costBudget = CostBudget(maxUsdCents = 0.0))
                .permitsCloud(),
        )

        assertTrue(AiRoutePolicies.positionChat.permitsCloud())
        assertTrue(AiRoutePolicies.openingExplainer.permitsCloud())
        assertFalse(AiRoutePolicies.moveCoachOffline.permitsCloud())
        assertFalse(AiRoutePolicies.rulesQaOffline.permitsCloud())
    }

    @Test
    fun `position chat with a cloud-allowed policy reaches cloud`() {
        // Regression guard for the load-bearing `isDeviceModelAvailable = false` in
        // KtorStreamingChatClient / KtorOpeningExplainerClient. The decider prefers a local route
        // whenever a device model is reported available, and neither surface has an on-device
        // implementation — so reporting `true` there silently sends both to the offline fallback.
        // If someone "tidies" those literals to `true`, this test is what should fail.
        val cloudOnlySurfaceContext = context(
            isDeviceModelAvailable = false,
            isNetworkAvailable = true,
        )
        assertEquals(
            AiRoutePolicyDecider.Decision.RunCloud,
            AiRoutePolicyDecider.decide(AiRoutePolicies.positionChat, cloudOnlySurfaceContext),
        )
        assertEquals(
            AiRoutePolicyDecider.Decision.RunCloud,
            AiRoutePolicyDecider.decide(AiRoutePolicies.openingExplainer, cloudOnlySurfaceContext),
        )
    }
}
