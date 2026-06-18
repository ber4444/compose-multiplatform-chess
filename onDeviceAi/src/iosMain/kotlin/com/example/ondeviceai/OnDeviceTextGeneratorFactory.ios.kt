package com.example.ondeviceai

actual fun defaultOnDeviceTextGeneratorFactory(): OnDeviceTextGeneratorFactory =
    OnDeviceTextGeneratorFactory {
        FoundationModelsBridgeRegistry.provider?.create()
    }
