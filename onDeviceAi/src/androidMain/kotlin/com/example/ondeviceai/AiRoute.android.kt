package com.example.ondeviceai

actual suspend fun probeAvailableLocalVendors(): List<VendorRoute> {
    val vendors = mutableListOf<VendorRoute>()
    
    val mlKit = MlKitPromptGenerator(ModelPreference.FAST)
    var status = mlKit.status()
    android.util.Log.d("AiRoute", "MLKit status: $status")
    // A feature that is merely *downloadable* is not absent. Without this the probe reported
    // Unavailable, the decider never picked ML Kit, warmup() never ran, and the download it would
    // have triggered never happened — AICore looked missing on hardware that has it.
    if (status is AiAvailability.Downloadable) {
        mlKit.warmup()
        status = mlKit.status()
        android.util.Log.d("AiRoute", "MLKit status after download: $status")
    }
    if (status is AiAvailability.Available) {
        vendors.add(VendorRoute.MlKitPrompt(ModelPreference.FAST))
    }
    
    if (isCactusInitialized()) {
        vendors.add(VendorRoute.CactusLocal())
    }
    
    return vendors
}
