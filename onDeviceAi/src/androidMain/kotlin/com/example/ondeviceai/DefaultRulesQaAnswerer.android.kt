package com.example.ondeviceai

import com.example.ondeviceai.cactus.StructuredOutputRulesQaAnswerer

actual fun defaultRulesQaAnswerer(lookupTool: RuleLookupTool): RulesQaAnswerer? =
    if (isCactusInitialized()) StructuredOutputRulesQaAnswerer(
        executor = VendorRouteExecutor(),
        lookupTool = lookupTool,
    ) else null
