package com.example.myapplication.monetization

internal actual fun revenueCatApiKey(debug: Boolean): String =
    if (debug) REVENUECAT_ANDROID_TEST_KEY.ifBlank { REVENUECAT_ANDROID_KEY } else REVENUECAT_ANDROID_KEY
