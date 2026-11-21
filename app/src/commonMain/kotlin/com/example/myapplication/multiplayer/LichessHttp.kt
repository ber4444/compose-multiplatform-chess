package com.example.myapplication.multiplayer

import com.example.myapplication.opening.openingExplainerHttpClientEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.utils.io.readLine
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal const val LICHESS_BASE_URL = "https://lichess.org"
internal val lichessJson = Json { ignoreUnknownKeys = true }
internal val lichessGameId = Regex("[A-Za-z0-9]{8}")
internal val lichessUsername = Regex("[A-Za-z0-9_-]{2,30}")

internal class LichessException(message: String, val status: Int? = null) : Exception(message)

internal class LichessRequestGate(
    private val nowMillis: () -> Long = TimeSource.Monotonic.markNow().let { origin ->
        { origin.elapsedNow().inWholeMilliseconds }
    },
) {
    private val mutex = Mutex()
    private var blockedUntil = 0L

    suspend fun check() = mutex.withLock {
        if (nowMillis() < blockedUntil) throw LichessException("Lichess is limiting requests. Try again in a minute.", 429)
    }

    suspend fun blocked() = mutex.withLock {
        blockedUntil = nowMillis() + 60_000L
    }
}

internal fun createLichessHttpClient(): HttpClient =
    HttpClient(openingExplainerHttpClientEngine()) {
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 10_000L }
    }

internal class LichessHttp(
    private val client: HttpClient,
    private val baseUrl: String,
    private val gate: LichessRequestGate,
    private val token: String? = null,
) {
    suspend fun text(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        type: ContentType = ContentType.Application.Json,
        form: Parameters? = null,
    ): String {
        gate.check()
        val response = client.request(baseUrl.trimEnd('/') + path) {
            configure(method, type, form)
            timeout { requestTimeoutMillis = 15_000L }
        }
        checkStatus(response)
        return response.bodyAsText()
    }

    fun <T : Any> stream(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        form: Parameters? = null,
        connected: T? = null,
        idleTimeoutMillis: Long? = 30_000L,
        decode: (JsonObject) -> T?,
    ): Flow<T> = channelFlow {
        gate.check()
        client.prepareRequest(baseUrl.trimEnd('/') + path) {
            configure(method, ContentType("application", "x-ndjson"), form)
        }.execute { response ->
            checkStatus(response)
            if (connected != null) send(connected)
            val body = response.bodyAsChannel()
            while (true) {
                // Wrap EOF so it is distinguishable from an elapsed timeout.
                val line = if (idleTimeoutMillis == null) body.readLine() else {
                    val read = withTimeoutOrNull(idleTimeoutMillis) { Line(body.readLine()) }
                        ?: throw LichessException("The Lichess stream stopped responding.")
                    read.value
                }
                if (line == null) break
                if (line.isBlank()) continue
                val event = decode(lichessJson.decodeFromString<JsonObject>(line)) ?: continue
                send(event)
            }
        }
    }

    private fun HttpRequestBuilder.configure(httpMethod: HttpMethod, type: ContentType, form: Parameters?) {
        method = httpMethod
        accept(type)
        if (token != null) bearerAuth(token)
        if (form != null) setBody(FormDataContent(form))
    }

    private suspend fun checkStatus(response: HttpResponse) {
        val status = response.status.value
        if (status in 200..299) return
        if (status == 429) gate.blocked()
        val message = when (status) {
            400 -> "Lichess rejected the request."
            401, 403 -> "Lichess access expired or was denied. Sign in again."
            404 -> "Lichess could not find that game or account."
            429 -> "Lichess is limiting requests. Try again in a minute."
            else -> "Lichess returned an error."
        }
        // Provider bodies and URLs can contain private identifiers or authorization data.
        throw LichessException(message, status)
    }

    private data class Line(val value: String?)
}
