package com.example.ondeviceai


actual fun defaultRulesQaAnswerer(lookupTool: RuleLookupTool): RulesQaAnswerer? =
    OnDeviceRulesQaAnswerer(
        executor = VendorRouteExecutor(),
        lookupTool = lookupTool,
    )
