package com.example.ondeviceai

object AiRoutePolicyDecider {

    fun decide(policy: AiRoutePolicy, context: AiContextSnapshot): Decision {
        if (!context.isAppForegrounded) {
            return Decision.FallBack(FALLBACK_BACKGROUND)
        }

        val cloudAllowedByPolicy = policy.permitsCloud()

        val effectiveOfflineOnly = policy.requireOffline ||
            policy.privacyClass == PrivacyClass.LOCAL_ONLY ||
            context.userSetting == AiUserSetting.OFFLINE_ONLY

        if (context.thermalState == ThermalState.CRITICAL) {
            return if (cloudAllowedByPolicy && context.isNetworkAvailable) Decision.RunCloud
            else Decision.FallBack(FALLBACK_THERMAL)
        }

        val vendorRoute = resolveVendorRoute(policy, context)

        return when {
            vendorRoute != null -> Decision.Route(vendorRoute)
            effectiveOfflineOnly -> Decision.FallBack(FALLBACK_NO_LOCAL_MODEL)
            cloudAllowedByPolicy && context.isNetworkAvailable -> Decision.RunCloud
            cloudAllowedByPolicy -> Decision.FallBack(FALLBACK_NO_NETWORK)
            else -> Decision.FallBack(FALLBACK_NO_ROUTE)
        }
    }

    sealed interface Decision {
        data class Route(val route: VendorRoute) : Decision
        data object RunCloud : Decision
        data class FallBack(val reason: String) : Decision
    }

    const val FALLBACK_NO_LOCAL_MODEL = "no local model"
    const val FALLBACK_NO_NETWORK = "no network and no local model"
    const val FALLBACK_NO_ROUTE = "no route satisfies the policy"
    const val FALLBACK_BACKGROUND = "app backgrounded; ML Kit unavailable"
    const val FALLBACK_THERMAL = "thermal state too high for inference"
    const val FALLBACK_QUOTA = "platform quota/busy"
    const val FALLBACK_VALIDATION = "model output failed validation"
    const val FALLBACK_TIMEOUT = "inference exceeded latency budget"
}
