package com.example.myapplication.chat

import com.example.coachapi.ChatStreamEvent
import com.example.coachapi.PositionChatRequest
import com.example.myapplication.opening.OPENING_EXPLAINER_BASE_URL
import com.example.myapplication.opening.openingExplainerHttpClientEngine
import com.example.ondeviceai.AiContextSnapshot
import com.example.ondeviceai.AiUserSetting
import com.example.ondeviceai.DefaultPositionChat
import com.example.ondeviceai.PositionChat
import com.example.ondeviceai.StreamingChatClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readLine
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json

/**
 * Cloud [StreamingChatClient] over the `POST /v1/positions/chat/stream` SSE endpoint.
 *
 * Consumption uses `preparePost{}.execute{}` with `bodyAsChannel()` so that **cancelling the
 * collecting Job closes the network connection** (Ktor propagates coroutine cancellation into the
 * request's structured-concurrency scope — no orphaned streams). Each SSE `data: <json>` line is
 * decoded into a [ChatStreamEvent]; blank lines (the SSE record separator) and non-`data:` lines
 * are ignored. A `prepare`-based call also lets the server stream the chunked response back without
 * buffering it whole.
 *
 * Built with [channelFlow] rather than a plain `flow {}` because the emissions happen from inside
 * Ktor's `execute {}` block, which resumes on the [HttpClient]'s own dispatcher. A plain `flow {}`
 * enforces context-preservation and throws "Flow invariant is violated" when the producer and
 * collector contexts differ — which they do on wasm/iOS, where the engine's dispatcher is not the
 * test/UI dispatcher. `channelFlow`/`send` is the documented bridge for emitting across coroutine
 * contexts; its structured concurrency still propagates collector cancellation back into `execute`
 * (closing the connection), preserving the hard cancellation rule.
 */
class KtorStreamingChatClient(
    private val httpClient: HttpClient,
    baseUrl: String,
) : StreamingChatClient {
    private val endpoint = "${baseUrl.trimEnd('/')}/v1/positions/chat/stream"

    init {
        require(baseUrl.isNotBlank()) { "position-chat base URL must not be blank" }
    }

    override fun stream(request: PositionChatRequest): Flow<ChatStreamEvent> = channelFlow {
        // preparePost returns an HttpStatement; execute{} streams the response body. Cancelling this
        // flow's collector cancels the channelFlow scope, which cancels the execute{} block, which
        // closes the underlying connection — no orphaned streams (the plan's hard cancellation rule).
        val statement = httpClient.preparePost(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        statement.execute { response: HttpResponse ->
            if (response.status.value !in 200..299) return@execute
            val channel = response.bodyAsChannel()
            val sink: SendChannel<ChatStreamEvent> = this@channelFlow
            while (!channel.isClosedForRead) {
                val line = channel.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                val event = runCatching { EVENT_JSON.decodeFromString(ChatStreamEvent.serializer(), payload) }
                    .getOrNull() ?: continue
                sink.send(event)
                // A terminal event (done/fallback/error) ends the turn client-side too.
                if (event.type == ChatStreamEvent.TYPE_DONE ||
                    event.type == ChatStreamEvent.TYPE_FALLBACK ||
                    event.type == ChatStreamEvent.TYPE_ERROR
                ) break
            }
        }
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        // Ignore unknown keys so a server-side field addition doesn't break streaming consumption.
        private val EVENT_JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Builds the [PositionChat] orchestration entry point, mirroring [createOpeningExplainer]. The cloud
 * client is `null` (→ deterministic fallback) when no server base URL is configured; otherwise it
 * shares the same per-platform HTTP engine as the opening explainer.
 */
fun createPositionChat(): PositionChat {
    val baseUrl = OPENING_EXPLAINER_BASE_URL.trim()
    val client = baseUrl.takeIf(String::isNotEmpty)?.let {
        KtorStreamingChatClient(
            httpClient = HttpClient(openingExplainerHttpClientEngine()) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            },
            baseUrl = it,
        )
    }
    return DefaultPositionChat(
        client = client,
        contextProvider = {
            AiContextSnapshot(
                isDeviceModelAvailable = false,
                isNetworkAvailable = client != null,
                isAppForegrounded = true,
                userSetting = AiUserSetting.ALLOW_CLOUD,
            )
        },
    )
}
