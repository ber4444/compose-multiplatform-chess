package com.example.ondeviceai

/**
 * Reports which local vendors this device can actually run, most-preferred-first.
 *
 * Both [ModelPreference]s are tried. FAST and FULL select different base models and therefore ask
 * AICore for different features, so a device provisioned for one and not the other used to look
 * like a device with no ML Kit at all: only FAST was ever constructed here, and
 * `VendorRoute.MlKitPrompt(FULL)` — reachable by type — was built nowhere in the codebase. FAST
 * stays first because the coach panel is latency-bound; FULL is a fallback, not an upgrade path.
 */
actual suspend fun probeAvailableLocalVendors(): List<VendorRoute> {
    val vendors = mutableListOf<VendorRoute>()

    for (preference in listOf(ModelPreference.FAST, ModelPreference.FULL)) {
        val mlKit = MlKitPromptGenerator(preference)
        try {
            var status = mlKit.status()
            android.util.Log.d("AiRoute", "MLKit[$preference] status: $status")
            // A feature that is merely *downloadable* is not absent. Without this the probe reported
            // Unavailable, the decider never picked ML Kit, warmup() never ran, and the download it
            // would have triggered never happened — AICore looked missing on hardware that has it.
            //
            // Downloading belongs here too, and its omission was the remaining half of the same bug.
            // The Google sample branches AVAILABLE → run, UNAVAILABLE → error, *everything else* →
            // download and await completion (BaseActivity.checkFeatureStatus). A device with an
            // AICore fetch already in flight reports DOWNLOADING, and treating that as "no ML Kit"
            // records a provisioning delay as a device verdict — this probe runs once per process,
            // so nothing ever re-checks.
            if (status is AiAvailability.Downloadable || status is AiAvailability.Downloading) {
                mlKit.warmup()
                status = mlKit.status()
                android.util.Log.d("AiRoute", "MLKit[$preference] status after download: $status")
            }
            if (status is AiAvailability.Available) {
                vendors.add(VendorRoute.MlKitPrompt(preference))
            }
        } finally {
            // These are throwaway clients, not the executor's cached ones — the cache is keyed by
            // preference and built separately in VendorRouteExecutor.execute. Closing them here is
            // not the "borrow and return" release() the interface warns about; leaving them open
            // leaked a GenerativeModel per probe call, and the bench calls this repeatedly inside
            // the window it is timing.
            runCatching { mlKit.release() }
        }
    }

    return vendors
}
