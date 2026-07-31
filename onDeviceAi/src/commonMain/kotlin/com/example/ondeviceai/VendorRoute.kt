package com.example.ondeviceai

enum class ModelPreference { FAST, FULL }

/**
 * A concrete local runtime the decider can select. Platforms report which of these they can run via
 * [probeAvailableLocalVendors]; [VendorRouteExecutor] turns the one it is handed into a generator.
 *
 * [isCloudCapable] is **not** currently true for any variant, and that is the intended state: cloud
 * means `:server`, which owns pgvector retrieval, the grounding validators, and the cost ceiling.
 * The property stays on the interface so a future off-device vendor has to declare itself, and
 * `AiRoutePolicyDecider` filters on it before ever handing a route to a policy that forbids cloud.
 * `no shipped vendor route is cloud-capable` in `AiRoutePolicyDeciderTest` pins today's answer — if
 * you add a cloud-capable vendor, that test is where you say so deliberately.
 */
sealed interface VendorRoute {
    val isCloudCapable: Boolean

    data class MlKitPrompt(val preference: ModelPreference = ModelPreference.FAST) : VendorRoute { override val isCloudCapable = false }
    data class CactusLocal(val modelId: String = "default") : VendorRoute { override val isCloudCapable = false }
    data class AppleFoundationModels(val profileId: String = "LOCAL_ONLY") : VendorRoute { override val isCloudCapable = false }
    data class LiteRtLm(val preference: ModelPreference = ModelPreference.FAST) : VendorRoute { override val isCloudCapable = false }
}
