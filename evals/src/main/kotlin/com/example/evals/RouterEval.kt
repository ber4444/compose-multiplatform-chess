package com.example.evals

import com.example.ondeviceai.AiContextSnapshot
import com.example.ondeviceai.AiRoutePolicies
import com.example.ondeviceai.AiRoutePolicy
import com.example.ondeviceai.AiRoutePolicyDecider
import com.example.ondeviceai.AiUserSetting
import com.example.ondeviceai.PrivacyClass
import com.example.ondeviceai.ThermalState
import com.example.ondeviceai.VendorRoute

/**
 * Vocabulary of structural routing invariants (B13).
 */
enum class RoutingInvariant {
    NEVER_REACHES,
    ALWAYS_REACHES,
    CARRIES,
    DECLARES,
}

/**
 * Declarative expectation entry for testing routing decisions without reimplementing decider precedence (B13).
 */
data class ExpectationRule(
    val policy: AiRoutePolicy,
    val isAppForegrounded: Boolean,
    val isNetworkAvailable: Boolean,
    val hasLocalVendor: Boolean,
    val expectedDecisionClass: String,
)

object DeclarativeExpectationTable {
    val RULES: List<ExpectationRule> = listOf(
        // Backgrounded app always falls back regardless of vendors or network
        ExpectationRule(AiRoutePolicies.moveCoachOffline, isAppForegrounded = false, isNetworkAvailable = true, hasLocalVendor = true, expectedDecisionClass = "FallBack"),
        ExpectationRule(AiRoutePolicies.openingExplainer, isAppForegrounded = false, isNetworkAvailable = true, hasLocalVendor = true, expectedDecisionClass = "FallBack"),
        // Local-only move coach routes to device when local vendor is available
        ExpectationRule(AiRoutePolicies.moveCoachOffline, isAppForegrounded = true, isNetworkAvailable = true, hasLocalVendor = true, expectedDecisionClass = "RunOnDevice"),
        // Cloud opening explainer routes to cloud when network is available even if local vendor is available
        ExpectationRule(AiRoutePolicies.openingExplainer, isAppForegrounded = true, isNetworkAvailable = true, hasLocalVendor = true, expectedDecisionClass = "RunCloud"),
        // Cloud opening explainer falls back to NO_NETWORK when network is unavailable
        ExpectationRule(AiRoutePolicies.openingExplainer, isAppForegrounded = true, isNetworkAvailable = false, hasLocalVendor = true, expectedDecisionClass = "FallBack"),
    )
}

/**
 * Builds Cartesian product context snapshots across declared axes (B13).
 */
object ContextSweepBuilder {
    val vendorsAxis = listOf(
        emptyList(),
        listOf(VendorRoute.LiteRtLm()),
        listOf(VendorRoute.CactusLocal()),
    )
    val networkAxis = listOf(false, true)
    val foregroundAxis = listOf(false, true)
    val settingAxis = AiUserSetting.entries
    val thermalAxis = ThermalState.entries

    fun buildAllSnapshots(): List<AiContextSnapshot> {
        return vendorsAxis.flatMap { vendors ->
            networkAxis.flatMap { net ->
                foregroundAxis.flatMap { fore ->
                    settingAxis.flatMap { setting ->
                        thermalAxis.map { thermal ->
                            AiContextSnapshot(
                                availableLocalVendors = vendors,
                                isNetworkAvailable = net,
                                isAppForegrounded = fore,
                                userSetting = setting,
                                thermalState = thermal,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Evaluates routing policy decisions and invariant violations across context sweeps (B13).
 * Resides exclusively in :evals module to prevent leaking into public API of :onDeviceAi.
 */
object RouterEvalSuite {

    data class EvaluationResult(
        val totalEvaluated: Int,
        val violations: Int,
        val neverReachesViolations: Int,
        val alwaysReachesViolations: Int,
        val carriesViolations: Int,
        val declaresViolations: Int,
    ) {
        val isSuccess: Boolean get() = violations == 0
    }

    fun evaluate(
        policies: List<AiRoutePolicy> = listOf(
            AiRoutePolicies.moveCoachOffline,
            AiRoutePolicies.rulesQaOffline,
            AiRoutePolicies.openingExplainer,
            AiRoutePolicies.positionChat,
        ),
        snapshots: List<AiContextSnapshot> = ContextSweepBuilder.buildAllSnapshots(),
        testBypassPrivacy: Boolean = false,
    ): EvaluationResult {
        var total = 0
        var neverReaches = 0
        var alwaysReaches = 0
        var carries = 0
        var declares = 0

        for (policy in policies) {
            for (snapshot in snapshots) {
                total++
                val decision = AiRoutePolicyDecider.decide(policy, snapshot, testBypassPrivacy = testBypassPrivacy)

                // 1. NEVER_REACHES invariant
                if (policy.privacyClass == PrivacyClass.LOCAL_ONLY || policy.requireOffline || !policy.allowCloud) {
                    if (decision is AiRoutePolicyDecider.Decision.RunCloud) {
                        neverReaches++
                    }
                    if (decision is AiRoutePolicyDecider.Decision.RunOnDevice && decision.route.isCloudCapable) {
                        neverReaches++
                    }
                }

                // 2. ALWAYS_REACHES invariant
                if (!policy.allowLocal && policy.allowCloud && snapshot.isAppForegrounded && snapshot.isNetworkAvailable && snapshot.userSetting != AiUserSetting.OFFLINE_ONLY && snapshot.thermalState != ThermalState.CRITICAL) {
                    if (decision !is AiRoutePolicyDecider.Decision.RunCloud) {
                        alwaysReaches++
                    }
                }

                // 3. CARRIES invariant
                if (decision is AiRoutePolicyDecider.Decision.RunOnDevice) {
                    if (!snapshot.availableLocalVendors.contains(decision.route)) {
                        carries++
                    }
                }
            }
        }

        // 4. DECLARES invariant (exhaustive taxonomy verification)
        val allRoutes: List<VendorRoute> = listOf(
            VendorRoute.MlKitPrompt(),
            VendorRoute.CactusLocal(),
            VendorRoute.AppleFoundationModels(),
            VendorRoute.LiteRtLm(),
        )
        for (route in allRoutes) {
            val declaredCloudCapability = when (route) {
                is VendorRoute.MlKitPrompt -> false
                is VendorRoute.CactusLocal -> false
                is VendorRoute.AppleFoundationModels -> false
                is VendorRoute.LiteRtLm -> false
            }
            if (declaredCloudCapability != route.isCloudCapable) {
                declares++
            }
        }

        val totalViolations = neverReaches + alwaysReaches + carries + declares
        return EvaluationResult(
            totalEvaluated = total,
            violations = totalViolations,
            neverReachesViolations = neverReaches,
            alwaysReachesViolations = alwaysReaches,
            carriesViolations = carries,
            declaresViolations = declares,
        )
    }

    /**
     * Perturbs the decider code under test and verifies that [evaluate] produces violations (B13).
     */
    fun mutationTestRoutingInvariants(): Boolean {
        val result = evaluate(testBypassPrivacy = true)
        return !result.isSuccess && result.violations > 0
    }
}
