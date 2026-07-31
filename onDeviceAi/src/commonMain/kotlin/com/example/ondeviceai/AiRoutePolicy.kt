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
    val allowLocal: Boolean = true,
    val requireOffline: Boolean,
)

/**
 * True when this policy may reach a paid/off-device route at all.
 *
 * Single source of truth for that question: [AiRoutePolicyDecider] uses it to decide whether
 * `RunCloud` is even reachable, and platform `resolveVendorRoute` implementations use it before
 * selecting a **cloud-capable vendor** (e.g. Firebase hybrid inference). Keeping one predicate
 * means a vendor route cannot disagree with the decider about what the policy permits.
 *
 * All four conditions matter — in particular `allowCloud` is *not* implied by the others: a policy
 * can be non-`LOCAL_ONLY` and non-`requireOffline` yet still have `allowCloud = false`, and such a
 * policy must never be handed a cloud vendor.
 */
internal fun AiRoutePolicy.permitsCloud(): Boolean =
    allowCloud &&
        !requireOffline &&
        privacyClass != PrivacyClass.LOCAL_ONLY &&
        costBudget.maxUsdCents > 0.0

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
        allowLocal = false,
        requireOffline = false,
    )

    /**
     * Interactive, multi-turn position chat. Public position data (FEN/SAN/ECO/locale) plus a single
     * bounded chess question per turn — no user identifiers, no device data, no PII (the server
     * re-pins retrieval every turn so grounding never drifts). Cloud-only by design: there is no
     * on-device chat implementation (the local generators stay scoped to the move coach / summary),
     * and the streaming UX needs first-token latency a cloud provider gives. The latency budget is
     * tuned for first-token streaming; [moveCoachOffline] stays LOCAL_ONLY and never reaches cloud.
     */
    val positionChat = AiRoutePolicy(
        privacyClass = PrivacyClass.PUBLIC_OR_SYNTHETIC,
        latencyBudget = LatencyBudget(firstTokenMs = 2500, completeMs = 20_000),
        costBudget = CostBudget(maxUsdCents = 0.2),
        allowCloud = true,
        allowLocal = false,
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
