package com.example.ondeviceai

sealed interface AiRoute {
    data object OnDevice : AiRoute
    data object Cloud : AiRoute
    data class Fallback(val reason: AiRoutePolicyDecider.FallbackReason) : AiRoute
}

enum class AiUserSetting {
    OFFLINE_ONLY,
    PREFER_LOCAL,
    ALLOW_CLOUD,
}

enum class ThermalState {
    NOMINAL,
    FAIR,
    SERIOUS,
    CRITICAL,
    UNKNOWN,
}

data class AiContextSnapshot(
    val availableLocalVendors: List<VendorRoute> = emptyList(),
    val isNetworkAvailable: Boolean = false,
    val isAppForegrounded: Boolean = true,
    val userSetting: AiUserSetting = AiUserSetting.OFFLINE_ONLY,
    val thermalState: ThermalState = ThermalState.UNKNOWN,
) {
    val isDeviceModelAvailable: Boolean get() = availableLocalVendors.isNotEmpty()
}

expect suspend fun probeAvailableLocalVendors(): List<VendorRoute>
