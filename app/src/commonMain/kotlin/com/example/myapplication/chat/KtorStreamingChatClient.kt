package com.example.myapplication.chat

import co.touchlab.kermit.Logger
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
import io.ktor.client.plugins.HttpTimeout
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeout
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
    private val streamTimeoutMs: Long = STREAM_TIMEOUT_MS,
) : StreamingChatClient {
    private val endpoint = "${baseUrl.trimEnd('/')}/v1/positions/chat/stream"

    init {
        require(baseUrl.isNotBlank()) { "position-chat base URL must not be blank" }
    }

    override fun stream(request: PositionChatRequest): Flow<ChatStreamEvent> = channelFlow {
        val sink: SendChannel<ChatStreamEvent> = this@channelFlow
        try {
            // Belt-and-suspenders on top of the HttpClient's own HttpTimeout: Ktor's socket/request
            // timeouts have historically not reliably bounded a response body read manually via
            // bodyAsChannel() from inside execute{} on the CIO engine, which can leave this stuck
            // forever with no exception (the app-side symptom: an indefinite "thinking" spinner with
            // no error and no retry). This withTimeout is engine-independent — it bounds the whole
            // call/response/stream regardless of where in Ktor's internals a stall happens.
            withTimeout(streamTimeoutMs) {
                logger.i { "position-chat: POST $endpoint" }
                // preparePost returns an HttpStatement; execute{} streams the response body.
                // Cancelling this flow's collector cancels the channelFlow scope, which cancels the
                // execute{} block, which closes the underlying connection — no orphaned streams (the
                // plan's hard cancellation rule).
                val statement = httpClient.preparePost(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                statement.execute { response: HttpResponse ->
                    logger.i { "position-chat: response status=${response.status.value}" }
                    if (response.status.value !in 200..299) {
                        logger.w { "position-chat: non-2xx status ${response.status.value} — aborting stream" }
                        return@execute
                    }
                    val channel = response.bodyAsChannel()
                    // Use null-termination rather than isClosedForRead: with Ktor's MockEngine
                    // (and some CIO scenarios) the channel reports isClosedForRead=true after the
                    // first chunk even when more SSE lines remain in the buffer, causing early exit.
                    // readLine() returns null only when the stream is genuinely exhausted.
                    while (true) {
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
        } catch (e: TimeoutCancellationException) {
            // Our own withTimeout firing — a real stall, not a caller-initiated cancellation. Convert
            // to a terminal event rather than rethrowing so the UI surfaces an error + retry instead
            // of silently swallowing this as ordinary structured-concurrency cancellation.
            logger.w { "position-chat: stream timed out after ${STREAM_TIMEOUT_MS}ms" }
            sink.send(errorEvent())
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Caller-initiated cancellation (Stop button, scope closed) — must propagate, not swallow.
            throw e
        } catch (e: Exception) {
            logger.e(e) { "position-chat: stream failed" }
            sink.send(errorEvent())
        }
    }

    override fun close() {
        httpClient.close()
    }

    private fun errorEvent() = ChatStreamEvent(type = ChatStreamEvent.TYPE_ERROR)

    companion object {
        // Ignore unknown keys so a server-side field addition doesn't break streaming consumption.
        internal val EVENT_JSON = Json { ignoreUnknownKeys = true }
        private val logger = Logger.withTag("PositionChat")

        /**
         * Parses an SSE body string (the full text of a `data: <json>\n\n`-delimited stream) into
         * a list of [ChatStreamEvent]s, stopping at the first terminal event. Exposed `internal` so
         * [KtorStreamingChatClientTest] can test SSE parsing in isolation from the HTTP transport.
         */
        internal fun parseSseBody(body: String): List<ChatStreamEvent> {
            val events = mutableListOf<ChatStreamEvent>()
            for (line in body.split("\n")) {
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                val event = runCatching {
                    EVENT_JSON.decodeFromString(ChatStreamEvent.serializer(), payload)
                }.getOrNull() ?: continue
                events += event
                if (event.type == ChatStreamEvent.TYPE_DONE ||
                    event.type == ChatStreamEvent.TYPE_FALLBACK ||
                    event.type == ChatStreamEvent.TYPE_ERROR
                ) break
            }
            return events
        }

        // Hard ceiling on one full request/stream turn (connect + headers + full SSE stream).
        // Generous relative to observed server latency (~7-11s including a cold LLM call), bounded
        // well short of "the user gives up and assumes the app is broken."
        private const val STREAM_TIMEOUT_MS = 45_000L
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
                // Without this, a stalled DNS/connect/TLS handshake or a server that accepts the
                // connection but never writes hangs the collecting flow forever with no exception —
                // the UI is stuck on its "thinking" indicator with no way to surface an error or retry.
                install(HttpTimeout) {
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 30_000
                    requestTimeoutMillis = 60_000
                }
            },
            baseUrl = it,
        )
    }
    return DefaultPositionChat(
        client = client,
        contextProvider = {
            AiContextSnapshot(
                // Belt-and-braces, not the guarantee. The cloud-only guarantee for this surface
                // lives on the policy: `AiRoutePolicies.positionChat` sets `allowLocal = false`, so
                // `AiRoutePolicyDecider` discards every local vendor regardless of what is reported
                // here. Pinned by `a policy with allowLocal=false never routes on-device even with
                // vendors available` in `AiRoutePolicyDeciderTest`.
                //
                // Kept empty because it is also *true* — there is no on-device chat generator, and
                // `DefaultPositionChat` treats any local decision as "no route". Probing real
                // vendors here would cost a real ML Kit availability check per turn to produce a
                // list the decider is contractually obliged to throw away.
                availableLocalVendors = emptyList(),
                isNetworkAvailable = client != null,
                isAppForegrounded = true,
                userSetting = AiUserSetting.ALLOW_CLOUD,
            )
        },
    )
}
