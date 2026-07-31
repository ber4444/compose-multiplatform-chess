package com.example.ondeviceai

actual suspend fun probeAvailableLocalVendors(): List<VendorRoute> {
    return listOf(VendorRoute.LiteRtLm())
}
