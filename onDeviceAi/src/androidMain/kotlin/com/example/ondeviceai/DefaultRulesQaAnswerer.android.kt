package com.example.ondeviceai

import com.example.ondeviceai.cactus.StructuredOutputRulesQaAnswerer

actual fun defaultRulesQaAnswerer(lookupTool: RuleLookupTool): RulesQaAnswerer? =
    if (isCactusInitialized()) StructuredOutputRulesQaAnswerer(
        factory = defaultOnDeviceTextGeneratorFactory(),
        lookupTool = lookupTool,
    ) else null
