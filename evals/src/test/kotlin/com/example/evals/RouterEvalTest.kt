package com.example.evals

import com.example.ondeviceai.AiContextSnapshot
import com.example.ondeviceai.AiRoutePolicyDecider
import com.example.ondeviceai.AiUserSetting
import com.example.ondeviceai.ThermalState
import com.example.ondeviceai.VendorRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouterEvalTest {

    @Test
    fun `declarative expectation table rules match decider outputs`() {
        for (rule in DeclarativeExpectationTable.RULES) {
            val snapshot = AiContextSnapshot(
                availableLocalVendors = if (rule.hasLocalVendor) listOf(VendorRoute.LiteRtLm()) else emptyList(),
                isNetworkAvailable = rule.isNetworkAvailable,
                isAppForegrounded = rule.isAppForegrounded,
                userSetting = AiUserSetting.ALLOW_CLOUD,
                thermalState = ThermalState.NOMINAL,
            )
            val decision = AiRoutePolicyDecider.decide(rule.policy, snapshot)
            val decisionClass = decision::class.simpleName
            assertEquals(
                rule.expectedDecisionClass,
                decisionClass,
                "Expected decision ${rule.expectedDecisionClass} for rule $rule but got $decision",
            )
        }
    }

    @Test
    fun `router eval suite sweep passes with zero violations`() {
        val result = RouterEvalSuite.evaluate()
        assertTrue(result.isSuccess, "Expected 0 violations across Cartesian sweep but got ${result.violations}")
    }

    @Test
    fun `decider code perturbation causes router sweep mutation test to go red`() {
        val mutationSuccess = RouterEvalSuite.mutationTestRoutingInvariants()
        assertTrue(mutationSuccess, "Mutation test should catch decider privacy bypass perturbation")
    }
}
