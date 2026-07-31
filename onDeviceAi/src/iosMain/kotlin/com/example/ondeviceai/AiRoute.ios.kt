package com.example.ondeviceai

actual suspend fun probeAvailableLocalVendors(): List<VendorRoute> {
    return if (FoundationModelsBridgeRegistry.provider != null) {
        listOf(VendorRoute.AppleFoundationModels())
    } else {
        emptyList()
    }
}
