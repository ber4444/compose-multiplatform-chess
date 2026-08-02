package com.example.ondeviceai

import com.example.coachapi.ChatStreamEvent
import com.example.coachapi.PositionChatRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Cloud client for `POST /v1/positions/chat/stream`. Returns a cold [Flow] of [ChatStreamEvent]s;
 * cancelling the collecting Job must close the underlying network connection (no orphaned streams).
 * Implemented per-target (Android/desktop/wasm share the Ktor client in `:app`; iOS could later back
 * this with Foundation Models behind the same interface, but chat is cloud-routed for now).
 */
fun interface StreamingChatClient {
    fun stream(request: PositionChatRequest): Flow<ChatStreamEvent>

    fun close() = Unit
}

/** Orchestration interface for position chat (mirrors [OpeningExplainer]). */
fun interface PositionChat {
    fun stream(request: PositionChatRequest): Flow<ChatStreamEvent>

    fun close() = Unit
}

/**
 * Routes a position-chat turn: cloud when the policy allows it, otherwise a deterministic fallback.
 * Mirrors [OpeningExplainer] but streams — tokens flow to the UI as they arrive, and validation
 * (forbidden phrases, citation/length) runs on the *accumulated* text at stream end on the server.
 *
 * If the route is Cloud but the client is unavailable, or the stream errors before producing
 * validated text, this emits a single [ChatStreamEvent] of type `fallback` / `error`. It never
 * substitutes local prose for a cloud-validated turn — chat is cloud-only by design (see
 * [AiRoutePolicies.positionChat]).
 */
class DefaultPositionChat(
    private val client: StreamingChatClient?,
    private val contextProvider: () -> AiContextSnapshot,
) : PositionChat {

    override fun stream(request: PositionChatRequest): Flow<ChatStreamEvent> = flow {
        when (val decision = AiRoutePolicyDecider.decide(AiRoutePolicies.positionChat, contextProvider())) {
            AiRoutePolicyDecider.Decision.RunCloud -> {
                val availableClient = client
                if (availableClient == null) {
                    emit(fallbackEvent(FALLBACK_CLOUD_ERROR))
                } else {
                    // Forward the cloud stream; the server validates the accumulated text and emits a
                    // terminal `fallback`/`done` event. A mid-stream provider error → fallback here.
                    emitAll(
                        availableClient.stream(request).catch { cause ->
                            if (cause is CancellationException) throw cause
                            emit(fallbackEvent(FALLBACK_CLOUD_ERROR))
                        },
                    )
                }
            }
            // No on-device chat implementation exists; an on-device decision is treated as no route.
            is AiRoutePolicyDecider.Decision.RunOnDevice -> emit(fallbackEvent(FALLBACK_NO_CHAT_MODEL))
            is AiRoutePolicyDecider.Decision.FallBack -> emit(fallbackEvent(decision.reason))
        }
    }

    override fun close() {
        client?.close()
    }

    private fun fallbackEvent(reason: AiRoutePolicyDecider.FallbackReason) = ChatStreamEvent(
        type = ChatStreamEvent.TYPE_FALLBACK,
        text = "Position chat is unavailable. Focus on central control, piece development, and king safety.",
        composerId = "offline-fallback",
    )

    companion object {
        // See DefaultOpeningExplainer's companion — same rationale.
        val FALLBACK_CLOUD_ERROR: AiRoutePolicyDecider.FallbackReason =
            AiRoutePolicyDecider.FallbackReason.Other("cloud position-chat service unavailable")
        val FALLBACK_NO_CHAT_MODEL: AiRoutePolicyDecider.FallbackReason =
            AiRoutePolicyDecider.FallbackReason.Other("no on-device position-chat model")
    }
}
