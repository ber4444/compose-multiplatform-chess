package com.example.ondeviceai

expect class VendorRouteExecutor() {
    suspend fun execute(route: VendorRoute): OnDeviceTextGenerator?
}
