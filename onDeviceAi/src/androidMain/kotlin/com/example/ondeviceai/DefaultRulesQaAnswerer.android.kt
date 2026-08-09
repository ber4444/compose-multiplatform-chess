package com.example.ondeviceai

import com.example.ondeviceai.cactus.OnDeviceRulesQaAnswerer

actual fun defaultRulesQaAnswerer(lookupTool: RuleLookupTool): RulesQaAnswerer? =
    OnDeviceRulesQaAnswerer(
        executor = VendorRouteExecutor(),
        lookupTool = lookupTool,
    )
