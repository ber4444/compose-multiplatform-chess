package com.example.ondeviceai

sealed interface AiRoute {
    data object OnDevice : AiRoute
    data object Cloud : AiRoute
    data class Fallback(val reason: String) : AiRoute
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

data class DeviceProfile(
    val tier: String,
    val supportsNpu: Boolean,
) {
    companion object {
        val UNKNOWN = DeviceProfile(tier = "unknown", supportsNpu = false)
    }
}

data class AiContextSnapshot(
    val isDeviceModelAvailable: Boolean,
    val isNetworkAvailable: Boolean = false,
    val isAppForegrounded: Boolean = true,
    val userSetting: AiUserSetting = AiUserSetting.OFFLINE_ONLY,
    val deviceProfile: DeviceProfile = DeviceProfile.UNKNOWN,
    val thermalState: ThermalState = ThermalState.UNKNOWN,
)
