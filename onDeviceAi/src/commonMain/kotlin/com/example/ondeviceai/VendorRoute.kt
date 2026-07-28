package com.example.ondeviceai

sealed interface VendorRoute {
    data class MlKitPrompt(val preference: String = "FAST") : VendorRoute
    data class FirebaseHybrid(val mode: String = "ONLY_ON_DEVICE") : VendorRoute
    data class CactusLocal(val modelId: String = "default") : VendorRoute
    data class AppleFoundationModels(val profileId: String = "LOCAL_ONLY") : VendorRoute
    data class LiteRtLm(val preference: String = "FAST") : VendorRoute
    data object DeterministicTemplate : VendorRoute
}
