package com.example.myapplication

/** Line-oriented pipe to a UCI engine. Implementations: WorkerUciTransport (wasm), FakeUciTransport (tests). */
interface UciTransport {
    /** Begin delivering engine output lines to [onLine]. Called once, before any send(). */
    fun start(onLine: (String) -> Unit)
    fun send(command: String)
    fun close()
}
