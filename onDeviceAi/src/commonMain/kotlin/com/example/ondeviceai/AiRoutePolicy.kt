package com.example.ondeviceai

enum class PrivacyClass {
    LOCAL_ONLY,
    USER_PRIVATE,
    PUBLIC_OR_SYNTHETIC,
}

data class LatencyBudget(
    val firstTokenMs: Long,
    val completeMs: Long,
)

data class CostBudget(
    val maxUsdCents: Double,
)

data class AiRoutePolicy(
    val privacyClass: PrivacyClass,
    val latencyBudget: LatencyBudget,
    val costBudget: CostBudget,
    val allowCloud: Boolean,
    val requireOffline: Boolean,
)

object AiRoutePolicies {
    val moveCoachOffline = AiRoutePolicy(
        privacyClass = PrivacyClass.LOCAL_ONLY,
        latencyBudget = LatencyBudget(firstTokenMs = 900, completeMs = 3500),
        costBudget = CostBudget(maxUsdCents = 0.0),
        allowCloud = false,
        requireOffline = true,
    )
}
