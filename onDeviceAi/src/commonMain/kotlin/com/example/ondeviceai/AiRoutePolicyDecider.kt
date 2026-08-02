package com.example.ondeviceai

object AiRoutePolicyDecider {

    fun decide(policy: AiRoutePolicy, context: AiContextSnapshot): Decision {
        if (!context.isAppForegrounded) {
            return Decision.FallBack(FallbackReason.Background)
        }

        val cloudAllowedByPolicy = policy.permitsCloud()

        val effectiveOfflineOnly = policy.requireOffline ||
            policy.privacyClass == PrivacyClass.LOCAL_ONLY ||
            context.userSetting == AiUserSetting.OFFLINE_ONLY

        if (context.thermalState == ThermalState.CRITICAL) {
            return if (cloudAllowedByPolicy && context.isNetworkAvailable) Decision.RunCloud
            else Decision.FallBack(FallbackReason.Thermal)
        }

        val eligibleVendors = if (!policy.allowLocal) {
            emptyList()
        } else {
            context.availableLocalVendors.filter { vendor ->
                !vendor.isCloudCapable || (cloudAllowedByPolicy && !effectiveOfflineOnly)
            }
        }

        return when {
            eligibleVendors.isNotEmpty() -> Decision.RunOnDevice(eligibleVendors.first())
            effectiveOfflineOnly -> Decision.FallBack(FallbackReason.NoLocalModel)
            cloudAllowedByPolicy && context.isNetworkAvailable -> Decision.RunCloud
            cloudAllowedByPolicy -> Decision.FallBack(FallbackReason.NoNetwork)
            else -> Decision.FallBack(FallbackReason.NoRoute)
        }
    }

    sealed interface Decision {
        data class RunOnDevice(val route: VendorRoute) : Decision
        data object RunCloud : Decision
        data class FallBack(val reason: FallbackReason) : Decision
    }

    sealed class FallbackReason(val description: String) {
        data object NoLocalModel : FallbackReason("no local model")
        data object NoNetwork : FallbackReason("no network and no local model")
        data object NoRoute : FallbackReason("no route satisfies the policy")
        data object Background : FallbackReason("app backgrounded; ML Kit unavailable")
        data object Thermal : FallbackReason("thermal state too high for inference")
        data object Quota : FallbackReason("platform quota/busy")
        data object Validation : FallbackReason("model output failed validation")
        data object Timeout : FallbackReason("inference exceeded latency budget")
        data class Other(val message: String) : FallbackReason(message)
    }
}
