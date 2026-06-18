package com.example.ondeviceai

object FoundationModelsBridgeRegistry {

    @kotlin.concurrent.Volatile
    private var _provider: Provider? = null

    val provider: Provider? get() = _provider

    fun register(provider: Provider) {
        _provider = provider
    }

    fun clear() {
        _provider = null
    }

    fun interface Provider {
        fun create(): OnDeviceTextGenerator?
    }
}
