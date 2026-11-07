package com.example.myapplication.multiplayer

import com.sun.net.httpserver.HttpServer
import io.ktor.http.Url
import java.net.InetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal actual fun onlineOAuthPlatform(openUri: (String) -> Unit): OnlineOAuthPlatform =
    object : JvmOnlineOAuthCrypto() {
        override suspend fun authorize(buildUrl: (String) -> String): OnlineOAuthCallback {
            val server = withContext(Dispatchers.IO) {
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 4)
            }
            try {
                val path = "/oauth/lichess"
                val redirect = "http://127.0.0.1:${server.address.port}$path"
                val url = buildUrl(redirect)
                val state = checkNotNull(Url(url).parameters["state"])
                val result = CompletableDeferred<OnlineOAuthCallback>()
                server.createContext(path) { exchange ->
                    exchange.use {
                        val request = exchange.requestURI
                        val callback = if (exchange.requestMethod == "GET" && request.rawPath == path) {
                            parseOnlineOAuthCallback("$redirect?${request.rawQuery.orEmpty()}", redirect, state)
                        } else null
                        val message = if (callback == null) {
                            "Invalid sign-in callback."
                        } else {
                            "You can close this tab and return to Chess."
                        }
                        val body = message.encodeToByteArray()
                        exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
                        exchange.responseHeaders.set("Cache-Control", "no-store")
                        exchange.sendResponseHeaders(if (callback == null) 400 else 200, body.size.toLong())
                        exchange.responseBody.write(body)
                        if (callback != null) result.complete(callback)
                    }
                }
                server.start()
                openUri(url)
                return withTimeoutOrNull(180_000) { result.await() }
                    ?: error("Lichess sign-in timed out.")
            } finally {
                server.stop(0)
            }
        }
    }
