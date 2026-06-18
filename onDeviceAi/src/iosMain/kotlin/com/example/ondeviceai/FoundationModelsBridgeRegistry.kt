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

/**
 * Swift-friendly registration helper. Kotlin/Native exposes `fun interface`
 *SAM types awkwardly to Swift (Swift has to adopt a generated protocol); this
 * helper takes a plain Kotlin function type, which Swift can pass a closure for
 * directly. The Swift side calls `registerFoundationModelsProvider { ... }`.
 */
fun registerFoundationModelsProvider(provider: () -> OnDeviceTextGenerator?) {
    FoundationModelsBridgeRegistry.register(
        FoundationModelsBridgeRegistry.Provider(provider)
    )
}
