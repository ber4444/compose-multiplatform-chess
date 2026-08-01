package com.example.ondeviceai

actual suspend fun probeAvailableLocalVendors(): List<VendorRoute> {
    val vendors = mutableListOf<VendorRoute>()
    
    val mlKit = MlKitPromptGenerator(ModelPreference.FAST)
    val status = mlKit.status()
    android.util.Log.d("AiRoute", "MLKit status: $status")
    if (status is AiAvailability.Available) {
        vendors.add(VendorRoute.MlKitPrompt(ModelPreference.FAST))
    }
    
    if (isCactusInitialized()) {
        vendors.add(VendorRoute.CactusLocal())
    }
    
    return vendors
}
