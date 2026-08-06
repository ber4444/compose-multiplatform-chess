package com.example.coachapi

import kotlinx.serialization.Serializable

/**
 * Wire models for the opening-explainer API.
 *
 * PUBLIC_OR_SYNTHETIC contract: requests contain chess position data and locale preferences only.
 * They must never gain user identifiers, account data, device identifiers, or free-form user text.
 */
@Serializable
data class OpeningExplainRequest(
    val fen: String,
    val movesSan: List<String>,
    val eco: String? = null,
    val locale: String? = null,
)

@Serializable
data class Passage(
    val sourceId: String,
    val title: String,
    val text: String,
)

@Serializable
data class OpeningExplainResponse(
    val text: String,
    val passages: List<Passage>,
    val composerId: String,
    val diagnostics: CloudDiagnostics? = null,
)

@Serializable
data class CorpusDiagnostics(
    val ready: Boolean,
    val seedVersion: String? = null,
    val rowCount: Int? = null,
    val finalSourceId: String? = null,
)

@Serializable
data class CloudDiagnostics(
    val releaseVersion: String,
    val corpus: CorpusDiagnostics,
    val retrievedPassageIds: List<String>,
    val composerId: String,
    val finishReason: String,
    val latencyMs: Long,
    val completionTokens: Int? = null,
    val rawProviderOutput: String? = null,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
)

/**
 * One turn of an interactive position-chat conversation.
 *
 * `role` is either "user" or "assistant"; `content` is the turn text. These are sent back to the
 * cloud composer so it has multi-turn context, but the cloud route re-pins the position/grounding
 * block on every turn (see [PositionChatRequest]).
 */
@Serializable
data class ChatTurn(
    val role: String,
    val content: String,
)

/**
 * Request body for `POST /v1/positions/chat/stream` — an interactive, multi-turn chat about a
 * single chess position.
 *
 * PUBLIC_OR_SYNTHETIC contract: requests carry chess position data (FEN, SAN plies, ECO), a
 * locale preference, the prior [history] of assistant/user turns, and a single bounded
 * [userMessage]. The [userMessage] is free-form *chess position text only* — a question or remark
 * about the position above — and is bounded in length (enforced server-side). Requests must never
 * carry user identifiers, account data, device identifiers, or non-chess PII; the server re-pins
 * retrieved grounding passages on every turn so the conversation stays anchored to the position.
 *
 * Bounds (enforced server-side, matching the opening-explainer request): [movesSan] ≤ 20 entries,
 * [history] ≤ 12 turns, [userMessage] ≤ 500 chars, [fen] ≤ 128 chars.
 */
@Serializable
data class PositionChatRequest(
    val fen: String,
    val movesSan: List<String>,
    val eco: String? = null,
    val locale: String? = null,
    val history: List<ChatTurn> = emptyList(),
    val userMessage: String,
)

/**
 * One event in the `POST /v1/positions/chat/stream` SSE response. Serialized with a `type`
 * discriminator so the `:onDeviceAi` streaming client (`:app`'s `KtorStreamingChatClient`) and the
 * `:server` producer share one wire format across the JVM server and the KMP client.
 *
 * - `token` — append [text] to the in-flight assistant message.
 * - `fallback` — the accumulated stream failed validation (or the provider errored); replace any
 *   streamed tokens with [text] (deterministic, validated) and mark the turn as a fallback.
 * - `done` — the turn completed and validated; [composerId] marks the producer (`llm-chat-v1` or
 *   `template-chat-v1`).
 * - `error` — the stream failed before producing any validated text (e.g. retrieval/route failure).
 */
@Serializable
data class ChatStreamEvent(
    val type: String,
    val text: String? = null,
    val composerId: String? = null,
    val diagnostics: CloudDiagnostics? = null,
) {
    companion object {
        const val TYPE_TOKEN = "token"
        const val TYPE_FALLBACK = "fallback"
        const val TYPE_DONE = "done"
        const val TYPE_ERROR = "error"
        const val TYPE_DIAGNOSTICS = "diagnostics"
    }
}
