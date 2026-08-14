package com.example.ondeviceai

actual suspend fun probeAvailableLocalVendors(): List<VendorRoute> {
    val vendors = mutableListOf<VendorRoute>()
    
    val mlKit = MlKitPromptGenerator(ModelPreference.FAST)
    var status = mlKit.status()
    android.util.Log.d("AiRoute", "MLKit status: $status")
    // A feature that is merely *downloadable* is not absent. Without this the probe reported
    // Unavailable, the decider never picked ML Kit, warmup() never ran, and the download it would
    // have triggered never happened — AICore looked missing on hardware that has it.
    //
    // Downloading belongs here too, and its omission was the remaining half of the same bug. The
    // Google sample branches AVAILABLE → run, UNAVAILABLE → error, *everything else* → download and
    // await completion (BaseActivity.checkFeatureStatus). A device with an AICore fetch already in
    // flight reports DOWNLOADING, and treating that as "no ML Kit" records a provisioning delay as
    // a device verdict — the probe runs once per process, so nothing ever re-checks.
    if (status is AiAvailability.Downloadable || status is AiAvailability.Downloading) {
        mlKit.warmup()
        status = mlKit.status()
        android.util.Log.d("AiRoute", "MLKit status after download: $status")
    }
    if (status is AiAvailability.Available) {
        vendors.add(VendorRoute.MlKitPrompt(ModelPreference.FAST))
    }


    return vendors
}
