package com.example.myapplication.chat

import com.example.coachapi.ChatStreamEvent
import com.example.coachapi.ChatTurn
import com.example.coachapi.PositionChatRequest
import com.example.myapplication.FenConverter
import com.example.myapplication.GameUiState
import com.example.ondeviceai.PositionChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One rendered message in the chat transcript. */
data class ChatMessage(
    val role: String, // "user" | "assistant"
    val text: String,
    /** `true` if this assistant turn arrived as a validation fallback rather than a cloud-validated reply. */
    val isFallback: Boolean = false,
)

/**
 * Immutable chat UI state. `partialText` is the in-flight assistant token buffer (rendered
 * token-by-token); when the turn completes it becomes a full [ChatMessage] in [messages].
 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val partialText: String = "",
    val error: Boolean = false,
    val canRetry: Boolean = false,
    /** `true` once at least one token has arrived for the in-flight turn (drives the typing indicator). */
    val firstTokenReceived: Boolean = false,
)

/**
 * Holds the multi-turn conversation for one position. Conversation state lives here (M4): the
 * position/grounding context is pinned into every request via [PositionChatRequest] and only the
 * last [MAX_HISTORY_TURNS] turns are sent back, dropping oldest pairs first so the context window
 * stays bounded (the server re-pins retrieval every turn, so grounding never drops out).
 *
 * Lifecycle: owned by a Compose state holder (like [com.example.myapplication.opening.OpeningExplainerStateHolder]),
 * constructed once per position and closed when the chat screen leaves composition. It owns a
 * [CoroutineScope] and a single [streamJob]; callers must call [close].
 */
class ChatViewModel(
    private val chat: PositionChat,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private var streamJob: Job? = null
    private var lastUserMessage: String? = null

    /** Sends [message] as a new user turn and streams the assistant reply into [state]. */
    fun send(gameState: GameUiState, message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || mutableState.value.streaming) return
        lastUserMessage = trimmed
        mutableState.value = mutableState.value.copy(
            messages = mutableState.value.messages + ChatMessage("user", trimmed),
            streaming = true,
            partialText = "",
            error = false,
            canRetry = false,
            firstTokenReceived = false,
        )
        stream(gameState, trimmed)
    }

    /** Cancels the in-flight stream (Stop button). Keeps the partial text visible and allows Retry. */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
        val current = mutableState.value
        mutableState.value = current.copy(
            streaming = false,
            // Promote any partial assistant text to a (marked) message so the transcript is honest,
            // then surface Retry so the user can re-issue the turn.
            messages = if (current.partialText.isBlank()) current.messages
            else current.messages + ChatMessage("assistant", current.partialText, isFallback = true),
            partialText = "",
            error = true,
            canRetry = true,
        )
    }

    /** Re-issues the last user turn (Retry button), discarding the failed/cancelled assistant reply. */
    fun retry(gameState: GameUiState) {
        val message = lastUserMessage ?: return
        if (mutableState.value.streaming) return
        // Drop trailing failed/cancelled assistant messages so the retry replaces them cleanly.
        val cleaned = mutableState.value.messages.dropLastWhile { it.role == "assistant" && it.isFallback }
        mutableState.value = mutableState.value.copy(
            messages = cleaned,
            streaming = true,
            partialText = "",
            error = false,
            canRetry = false,
            firstTokenReceived = false,
        )
        stream(gameState, message)
    }

    private fun stream(gameState: GameUiState, message: String) {
        streamJob?.cancel()
        streamJob = scope.launch {
            try {
                val history = boundedHistory()
                val request = PositionChatRequest(
                    fen = FenConverter.gameStateToFen(gameState),
                    movesSan = gameState.moveHistory.take(MAX_MOVES).map { it.san },
                    eco = null,
                    locale = null,
                    history = history,
                    userMessage = message,
                )
                chat.stream(request).collect { event -> handleEvent(event) }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // stop()/close() handle state; structured cancellation rethrows as expected.
                throw kotlinx.coroutines.CancellationException()
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    streaming = false,
                    partialText = "",
                    error = true,
                    canRetry = true,
                )
            }
        }
    }

    private fun handleEvent(event: ChatStreamEvent) {
        when (event.type) {
            ChatStreamEvent.TYPE_TOKEN -> {
                val current = mutableState.value
                mutableState.value = current.copy(
                    partialText = current.partialText + (event.text ?: ""),
                    firstTokenReceived = true,
                )
            }
            ChatStreamEvent.TYPE_FALLBACK -> {
                // Replace any streamed partial with the validated fallback text.
                finalizeAssistant(event.text ?: "", isFallback = true)
            }
            ChatStreamEvent.TYPE_DONE -> finalizeAssistant(mutableState.value.partialText, isFallback = false)
            ChatStreamEvent.TYPE_ERROR -> {
                mutableState.value = mutableState.value.copy(
                    streaming = false,
                    partialText = "",
                    error = true,
                    canRetry = true,
                )
            }
        }
    }

    private fun finalizeAssistant(text: String, isFallback: Boolean) {
        val current = mutableState.value
        mutableState.value = current.copy(
            messages = current.messages + ChatMessage("assistant", text, isFallback = isFallback),
            partialText = "",
            streaming = false,
            error = false,
            canRetry = false,
            firstTokenReceived = false,
        )
    }

    /**
     * Returns the bounded conversation history to send with the next request. The most recent user
     * turn is already appended to [ChatUiState.messages] by [send]; here we carry the prior turns
     * (assistant + user pairs) up to [MAX_HISTORY_TURNS], dropping oldest pairs first.
     */
    private fun boundedHistory(): List<ChatTurn> {
        val current = mutableState.value
        // Exclude the just-appended user turn (it's sent as `userMessage`); cap to the window.
        return current.messages
            .filter { it.role == "user" || it.role == "assistant" }
            .map { ChatTurn(it.role, it.text) }
            .takeLast(MAX_HISTORY_TURNS)
    }

    fun close() {
        streamJob?.cancel()
        scope.cancel()
        chat.close()
    }

    companion object {
        /** Bounds the moves list sent per request (server enforces ≤ 20). */
        const val MAX_MOVES = 20
        /**
         * Bounded multi-turn window (M4). Keeps the pinned position/grounding block (server-side) +
         * the last N turns; the server re-pins retrieval every turn so grounding never drops out.
         */
        const val MAX_HISTORY_TURNS = 6
    }
}
