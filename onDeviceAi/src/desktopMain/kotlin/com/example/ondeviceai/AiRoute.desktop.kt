package com.example.ondeviceai

// Desktop falls back to returning LiteRtLm directly, gated on environmental flags or settings if needed.
actual suspend fun probeAvailableLocalVendors(): List<VendorRoute> {
    // Desktop will always report LiteRtLm as available if coach is enabled.
    return listOf(VendorRoute.LiteRtLm())
}
