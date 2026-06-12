package com.example.myapplication

import co.touchlab.kermit.Logger

external class Worker(scriptURL: String) : JsAny {
    fun postMessage(message: JsString)
    fun terminate()
    var onmessage: (WorkerMessageEvent) -> Unit
}

external interface WorkerMessageEvent : JsAny {
    val data: JsAny?
}

class WorkerUciTransport(private val scriptUrl: String) : UciTransport {
    private var worker: Worker? = null

    companion object {
        private val logger = Logger.withTag("WorkerUciTransport")
    }

    override fun start(onLine: (String) -> Unit) {
        val w = Worker(scriptUrl)
        w.onmessage = { event ->
            event.data?.let { onLine(it.toString()) }   // stockfish.js posts plain JS strings
        }
        worker = w
    }

    override fun send(command: String) {
        worker?.postMessage(command.toJsString())
    }

    override fun close() {
        worker?.terminate()
        worker = null
    }
}
