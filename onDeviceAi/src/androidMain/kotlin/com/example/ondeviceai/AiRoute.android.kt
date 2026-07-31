package com.example.ondeviceai

actual suspend fun probeAvailableLocalVendors(): List<VendorRoute> {
    val vendors = mutableListOf<VendorRoute>()
    
    val mlKit = MlKitPromptGenerator(ModelPreference.FAST)
    if (mlKit.status() is AiAvailability.Available) {
        vendors.add(VendorRoute.MlKitPrompt(ModelPreference.FAST))
    }
    
    if (isCactusInitialized()) {
        vendors.add(VendorRoute.CactusLocal())
    }
    
    return vendors
}
