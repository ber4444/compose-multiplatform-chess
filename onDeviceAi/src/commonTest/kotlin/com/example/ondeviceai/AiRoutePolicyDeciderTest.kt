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
        availableLocalVendors = if (isDeviceModelAvailable) listOf(VendorRoute.LiteRtLm()) else emptyList(),
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
            for (vendors in listOf(emptyList(), listOf(VendorRoute.LiteRtLm()), listOf(VendorRoute.CactusLocal()))) {
                for (hasNetwork in listOf(false, true)) {
                    for (setting in AiUserSetting.entries) {
                        for (thermal in ThermalState.entries) {
                            add(
                                AiContextSnapshot(
                                    availableLocalVendors = vendors,
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

            // invariant test for LOCAL_ONLY ensuring !route.isCloudCapable
            if (decision is AiRoutePolicyDecider.Decision.RunOnDevice) {
                kotlin.test.assertTrue(!decision.route.isCloudCapable, "LOCAL_ONLY policy should never return a cloud-capable route")
            }
        }
    }

    @Test
    fun `position chat routes to cloud only under the allowed contexts across the 90-context sweep`() {
        // The decider grid: 3 (hasModel) × 2 (hasNetwork) × 3 (userSetting) × 5 (thermalState) = 90,
        // plus the foreground assumption. positionChat is cloud-only (allowLocal = false): it routes to Cloud exactly when
        // allowCloud(policy) && hasNetwork && !backgrounded && thermal != CRITICAL && underCostCeiling
        // AND the user hasn't pinned OFFLINE_ONLY. Because allowLocal = false, it will route to cloud even if a local model is present.
        val contexts = buildList {
            for (vendors in listOf(emptyList(), listOf(VendorRoute.LiteRtLm()), listOf(VendorRoute.CactusLocal()))) {
                for (hasNetwork in listOf(false, true)) {
                    for (setting in AiUserSetting.entries) {
                        for (thermal in ThermalState.entries) {
                            add(
                                AiContextSnapshot(
                                    availableLocalVendors = vendors,
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
                availableLocalVendors = emptyList(),
                isNetworkAvailable = true,
                isAppForegrounded = false,
                userSetting = AiUserSetting.ALLOW_CLOUD,
            ),
        )
        assertIs<AiRoutePolicyDecider.Decision.FallBack>(decision)
        assertEquals(AiRoutePolicyDecider.FALLBACK_BACKGROUND, decision.reason)
    }

    @Test
    fun `opening explainer with a local model routes to cloud when configured because allowLocal is false`() {
        val decision = AiRoutePolicyDecider.decide(
            AiRoutePolicies.openingExplainer,
            AiContextSnapshot(
                availableLocalVendors = listOf(VendorRoute.LiteRtLm()), // Local model available
                isNetworkAvailable = true,
                userSetting = AiUserSetting.ALLOW_CLOUD,
            ),
        )

        assertEquals(AiRoutePolicyDecider.Decision.RunCloud, decision)
    }

    private val moveCoachContext = AiContextSnapshot(
        availableLocalVendors = listOf(VendorRoute.LiteRtLm()),
        isAppForegrounded = true,
        userSetting = AiUserSetting.OFFLINE_ONLY,
    )

    @Test
    fun `LOCAL_ONLY with available model runs on-device`() {
        val decision = AiRoutePolicyDecider.decide(
            AiRoutePolicies.moveCoachOffline,
            moveCoachContext,
        )
        assertIs<AiRoutePolicyDecider.Decision.RunOnDevice>(decision)
        assertEquals(VendorRoute.LiteRtLm(), decision.route)
    }

    @Test
    fun `LOCAL_ONLY without model falls back even when cloud is configured`() {
        val cloudCapable = AiContextSnapshot(
            availableLocalVendors = emptyList(),
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
            availableLocalVendors = emptyList(),
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
            availableLocalVendors = listOf(VendorRoute.LiteRtLm()),
            isNetworkAvailable = true,
            isAppForegrounded = true,
            userSetting = AiUserSetting.ALLOW_CLOUD,
        )
        val decision = AiRoutePolicyDecider.decide(policy, context)
        assertIs<AiRoutePolicyDecider.Decision.RunOnDevice>(decision)
        assertEquals(VendorRoute.LiteRtLm(), decision.route)
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
            availableLocalVendors = emptyList(),
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
            availableLocalVendors = emptyList(),
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
            availableLocalVendors = emptyList(),
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
            availableLocalVendors = listOf(VendorRoute.LiteRtLm()),
            isNetworkAvailable = true,
            isAppForegrounded = true,
            userSetting = AiUserSetting.ALLOW_CLOUD,
        )
        val decision = AiRoutePolicyDecider.decide(policy, context)
        assertIs<AiRoutePolicyDecider.Decision.RunOnDevice>(decision)
        assertEquals(VendorRoute.LiteRtLm(), decision.route)
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
            availableLocalVendors = listOf(VendorRoute.LiteRtLm()),
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

    // --- Ported VendorRouteResolverTest cases -------------------------------------------------
    //
    // These originally fed `VendorRoute.FirebaseCloud` — the one cloud-capable vendor — into
    // `availableLocalVendors` and asserted the decider refused it for cloud-forbidding policies.
    // `FirebaseCloud` was deleted with `FirebaseCloudGenerator`: cloud now always means `:server`,
    // which owns retrieval, the grounding validators, and the cost ceiling. `VendorRoute` is a
    // sealed interface, so `commonTest` cannot mint a stand-in cloud vendor to keep exercising the
    // filter branch directly. What replaces it: `no shipped vendor route is cloud-capable` below
    // pins the reason the filter has nothing to reject, so reintroducing a cloud vendor has to be a
    // deliberate act that turns this test red first.

    /**
     * The `isCloudCapable` guard is only meaningful while someone maintains this list. Every
     * `VendorRoute` variant must appear here — the `when` makes the compiler enforce that, so
     * adding a variant fails to build until its cloud class is declared.
     */
    private val allVendorRoutes: List<VendorRoute> = listOf(
        VendorRoute.MlKitPrompt(),
        VendorRoute.CactusLocal(),
        VendorRoute.AppleFoundationModels(),
        VendorRoute.LiteRtLm(),
    )

    @Test
    fun `no shipped vendor route is cloud-capable`() {
        // Exhaustive `when` with no `else`: a new VendorRoute variant breaks this test's
        // compilation, which is the prompt to decide its cloud class deliberately.
        allVendorRoutes.forEach { route ->
            val expected = when (route) {
                is VendorRoute.MlKitPrompt -> false
                is VendorRoute.CactusLocal -> false
                is VendorRoute.AppleFoundationModels -> false
                is VendorRoute.LiteRtLm -> false
            }
            assertEquals(expected, route.isCloudCapable, "unexpected cloud class for $route")
        }
        assertTrue(
            allVendorRoutes.none { it.isCloudCapable },
            "cloud must mean :server — a cloud-capable vendor route bypasses retrieval, the " +
                "grounding validators, and the cost ceiling. Adding one is a deliberate decision.",
        )
    }

    @Test
    fun `LOCAL_ONLY policies fall back rather than reach cloud when no vendor is available`() {
        val cloudReachableContext = AiContextSnapshot(
            availableLocalVendors = emptyList(),
            isNetworkAvailable = true,
            isAppForegrounded = true,
            userSetting = AiUserSetting.ALLOW_CLOUD,
        )

        listOf(AiRoutePolicies.moveCoachOffline, AiRoutePolicies.rulesQaOffline).forEach { policy ->
            val decision = AiRoutePolicyDecider.decide(policy, cloudReachableContext)
            assertIs<AiRoutePolicyDecider.Decision.FallBack>(decision)
            assertEquals(AiRoutePolicyDecider.FALLBACK_NO_LOCAL_MODEL, decision.reason)
        }
    }

    @Test
    fun `a policy that forbids cloud cannot reach cloud even when not offline-only`() {
        // allowCloud=false is neither LOCAL_ONLY nor requireOffline, so `effectiveOfflineOnly` is
        // false — the precedence hole #108 closed. `permitsCloud()` is the predicate that catches it.
        val noCloud = AiRoutePolicies.positionChat.copy(allowCloud = false)
        assertFalse(noCloud.permitsCloud())

        val decision = AiRoutePolicyDecider.decide(
            noCloud,
            AiContextSnapshot(
                availableLocalVendors = emptyList(),
                isNetworkAvailable = true,
                isAppForegrounded = true,
                userSetting = AiUserSetting.ALLOW_CLOUD,
            ),
        )
        assertNotEquals(AiRoutePolicyDecider.Decision.RunCloud, decision)
    }

    @Test
    fun `a zero-budget policy cannot reach cloud`() {
        val noBudget = AiRoutePolicies.positionChat.copy(costBudget = CostBudget(maxUsdCents = 0.0))
        assertFalse(noBudget.permitsCloud())

        val decision = AiRoutePolicyDecider.decide(
            noBudget,
            AiContextSnapshot(
                availableLocalVendors = emptyList(),
                isNetworkAvailable = true,
                isAppForegrounded = true,
                userSetting = AiUserSetting.ALLOW_CLOUD,
            ),
        )
        assertNotEquals(AiRoutePolicyDecider.Decision.RunCloud, decision)
    }

    @Test
    fun `user OFFLINE_ONLY overrides a cloud-allowed policy`() {
        val decision = AiRoutePolicyDecider.decide(
            AiRoutePolicies.positionChat,
            AiContextSnapshot(
                availableLocalVendors = emptyList(),
                isNetworkAvailable = true,
                isAppForegrounded = true,
                userSetting = AiUserSetting.OFFLINE_ONLY,
            ),
        )
        assertIs<AiRoutePolicyDecider.Decision.FallBack>(decision)
        assertEquals(AiRoutePolicyDecider.FALLBACK_NO_LOCAL_MODEL, decision.reason)
    }

    // --- allowLocal: the cloud-only guarantee, independent of vendor availability ---------------

    /**
     * The point of `allowLocal = false`. Before it existed, "chat/explainer always reach `:server`"
     * held only because `KtorStreamingChatClient` and `KtorOpeningExplainerClient` happened to report
     * an empty vendor list — correct behaviour resting on two literals.
     *
     * Every other test in this file passes an empty list for the cloud surfaces, so none of them can
     * distinguish "the policy forbids local" from "no local vendor was offered". This one supplies a
     * genuinely available local vendor and asserts the decision is still Cloud.
     */
    @Test
    fun `a policy with allowLocal=false never routes on-device even with vendors available`() {
        val vendorRichContext = AiContextSnapshot(
            availableLocalVendors = listOf(VendorRoute.MlKitPrompt(), VendorRoute.CactusLocal()),
            isNetworkAvailable = true,
            isAppForegrounded = true,
            userSetting = AiUserSetting.ALLOW_CLOUD,
            thermalState = ThermalState.NOMINAL,
        )

        listOf(AiRoutePolicies.positionChat, AiRoutePolicies.openingExplainer).forEach { policy ->
            assertFalse(policy.allowLocal, "${policy.privacyClass} cloud surface must forbid local")
            assertEquals(
                AiRoutePolicyDecider.Decision.RunCloud,
                AiRoutePolicyDecider.decide(policy, vendorRichContext),
                "allowLocal=false must beat an available local vendor",
            )
        }
    }

    /**
     * The converse, so the flag is pinned in both directions: flipping `allowLocal` back to true is
     * a behaviour change the suite notices. Without this, deleting the `!policy.allowLocal` branch
     * in the decider would still pass the test above on any context where no vendor is offered.
     */
    @Test
    fun `the same policy with allowLocal=true does prefer an available local vendor`() {
        val vendorRichContext = AiContextSnapshot(
            availableLocalVendors = listOf(VendorRoute.CactusLocal()),
            isNetworkAvailable = true,
            isAppForegrounded = true,
            userSetting = AiUserSetting.ALLOW_CLOUD,
            thermalState = ThermalState.NOMINAL,
        )

        val decision = AiRoutePolicyDecider.decide(
            AiRoutePolicies.positionChat.copy(allowLocal = true),
            vendorRichContext,
        )
        assertIs<AiRoutePolicyDecider.Decision.RunOnDevice>(decision)
        assertEquals(VendorRoute.CactusLocal(), decision.route)
    }
}
