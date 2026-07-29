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
        latencyBudget = LatencyBudget(firstTokenMs = 5000, completeMs = 60000),
        costBudget = CostBudget(maxUsdCents = 0.0),
        allowCloud = false,
        requireOffline = true,
    )

    val openingExplainer = AiRoutePolicy(
        privacyClass = PrivacyClass.PUBLIC_OR_SYNTHETIC,
        latencyBudget = LatencyBudget(firstTokenMs = 2500, completeMs = 8000),
        costBudget = CostBudget(maxUsdCents = 0.2),
        allowCloud = true,
        requireOffline = false,
    )

    val rulesQaOffline = AiRoutePolicy(
        privacyClass = PrivacyClass.LOCAL_ONLY,
        latencyBudget = LatencyBudget(firstTokenMs = 5000, completeMs = 20000),
        costBudget = CostBudget(maxUsdCents = 0.0),
        allowCloud = false,
        requireOffline = true,
    )
}
