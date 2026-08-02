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
    val userSetting: AiUserSetting = AiUserSetting.ALLOW_CLOUD,
    val thermalState: ThermalState = ThermalState.NOMINAL,
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
        decider: (AiRoutePolicy, AiContextSnapshot) -> AiRoutePolicyDecider.Decision = { policy, snapshot ->
            AiRoutePolicyDecider.decide(policy, snapshot)
        },
    ): EvaluationResult {
        var total = 0
        var neverReaches = 0
        var alwaysReaches = 0
        var carries = 0
        var declares = 0

        for (policy in policies) {
            for (snapshot in snapshots) {
                total++
                val decision = decider(policy, snapshot)

                // 1. NEVER_REACHES invariant: LOCAL_ONLY or offline policy never reaches cloud
                if (policy.privacyClass == PrivacyClass.LOCAL_ONLY || policy.requireOffline || !policy.allowCloud) {
                    if (decision is AiRoutePolicyDecider.Decision.RunCloud) {
                        neverReaches++
                    }
                    if (decision is AiRoutePolicyDecider.Decision.RunOnDevice && decision.route.isCloudCapable) {
                        neverReaches++
                    }
                }

                // 2. ALWAYS_REACHES invariant: Declarative check against ExpectationTable rules
                val matchedRule = DeclarativeExpectationTable.RULES.find { rule ->
                    rule.policy.privacyClass == policy.privacyClass &&
                        rule.policy.allowCloud == policy.allowCloud &&
                        rule.policy.allowLocal == policy.allowLocal &&
                        rule.isAppForegrounded == snapshot.isAppForegrounded &&
                        rule.isNetworkAvailable == snapshot.isNetworkAvailable &&
                        rule.hasLocalVendor == snapshot.availableLocalVendors.isNotEmpty() &&
                        rule.userSetting == snapshot.userSetting &&
                        rule.thermalState == snapshot.thermalState
                }
                if (matchedRule != null) {
                    val actualClass = decision::class.simpleName ?: ""
                    if (actualClass != matchedRule.expectedDecisionClass) {
                        alwaysReaches++
                    }
                }

                // 3. CARRIES invariant: RunOnDevice must carry an available local vendor
                if (decision is AiRoutePolicyDecider.Decision.RunOnDevice) {
                    if (!snapshot.availableLocalVendors.contains(decision.route)) {
                        carries++
                    }
                }
            }
        }

        // 4. DECLARES invariant (exhaustive taxonomy verification against expected contracts)
        val allRoutes: List<VendorRoute> = listOf(
            VendorRoute.MlKitPrompt(),
            VendorRoute.CactusLocal(),
            VendorRoute.AppleFoundationModels(),
            VendorRoute.LiteRtLm(),
        )
        for (route in allRoutes) {
            val isDeclaredLocalOnly = when (route) {
                is VendorRoute.MlKitPrompt -> true
                is VendorRoute.CactusLocal -> true
                is VendorRoute.AppleFoundationModels -> true
                is VendorRoute.LiteRtLm -> true
            }
            if (isDeclaredLocalOnly && route.isCloudCapable) {
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
     * Injects a mutated decider lambda into [evaluate] to confirm sweeps flag privacy regressions (B13).
     * Does NOT alter the production signature of [AiRoutePolicyDecider.decide].
     */
    fun mutationTestRoutingInvariants(): Boolean {
        val mutatedDecider: (AiRoutePolicy, AiContextSnapshot) -> AiRoutePolicyDecider.Decision = { policy, snapshot ->
            // A bug in the decider that bypasses LOCAL_ONLY privacy checks and routes to cloud when network is available
            if (policy.privacyClass == PrivacyClass.LOCAL_ONLY && snapshot.isAppForegrounded && snapshot.isNetworkAvailable) {
                AiRoutePolicyDecider.Decision.RunCloud
            } else {
                AiRoutePolicyDecider.decide(policy, snapshot)
            }
        }
        val result = evaluate(decider = mutatedDecider)
        return !result.isSuccess && result.violations > 0
    }
}
