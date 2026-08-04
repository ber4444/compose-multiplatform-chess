package com.example.myapplication.monetization

internal actual fun revenueCatApiKey(debug: Boolean): String =
    if (debug) REVENUECAT_IOS_TEST_KEY.ifBlank { REVENUECAT_IOS_KEY } else REVENUECAT_IOS_KEY
