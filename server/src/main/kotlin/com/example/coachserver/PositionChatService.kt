package com.example.coachserver

import com.example.coachapi.ChatStreamEvent
import com.example.coachapi.ChatTurn
import com.example.coachapi.Passage
import com.example.coachapi.PositionChatRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * One unit of the streamed position-chat response.
 *
 * - [Token] carries a fragment of assistant text to append to the UI.
 * - [Fallback] signals that the accumulated stream failed validation (or the provider errored) and
 *   the deterministic [TemplateChatComposer] text should be shown instead. Streamed tokens already
 *   shown should be replaced by the fallback text — it is never persisted unvalidated into history.
 * - [Done] terminates the stream on a *validated* turn; [composerId] marks whether the turn was
 *   produced by the LLM (`llm-chat-v1`) or the template (`template-chat-v1`).
 */
sealed interface ChatChunk {
    data class Token(val text: String) : ChatChunk
    data class Fallback(val text: String) : ChatChunk
    data class Done(val composerId: String) : ChatChunk
}

/** Maps an internal [ChatChunk] to the shared [ChatStreamEvent] wire model. */
fun ChatChunk.toEvent(): ChatStreamEvent = when (this) {
    is ChatChunk.Token -> ChatStreamEvent(type = ChatStreamEvent.TYPE_TOKEN, text = text)
    is ChatChunk.Fallback -> ChatStreamEvent(
        type = ChatStreamEvent.TYPE_FALLBACK,
        text = text,
        composerId = TemplateChatComposer.ID,
    )
    is ChatChunk.Done -> ChatStreamEvent(type = ChatStreamEvent.TYPE_DONE, composerId = composerId)
}

/**
 * Streaming composer for position chat. Composes a grounded reply to [request] and streams it.
 *
 * Contract: implementations MUST validate the *accumulated* text with [PositionChatValidator] at
 * stream end; on failure they emit a single [ChatChunk.Fallback] carrying deterministic text and
 * never emit [ChatChunk.Done] with the unvalidated composer id.
 */
fun interface StreamingChatComposer {
    fun streamCompose(request: PositionChatRequest, passages: List<Passage>): Flow<ChatChunk>
}

/**
 * Deterministic, network-free composer — the fallback for the LLM composer and the default when no
 * provider key is configured. Emits a short grounded paragraph citing the top passage, capped to
 * [PositionChatValidator.MAX_OUTPUT_CHARS].
 */
class TemplateChatComposer : StreamingChatComposer {
    override fun streamCompose(request: PositionChatRequest, passages: List<Passage>): Flow<ChatChunk> = flow {
        val text = buildText(request, passages)
        // Pace the deterministic text into a few chunks so the streaming UX is exercised the same
        // way as the LLM path in tests/evals; on a real provider these arrive token-by-token.
        text.splitIntoChunks().forEach { emit(ChatChunk.Token(it)) }
        emit(ChatChunk.Done(ID))
    }

    private fun buildText(request: PositionChatRequest, passages: List<Passage>): String {
        val moveLine = request.movesSan.takeLast(4).joinToString(" ").ifBlank { "this position" }
        val grounded = when {
            passages.isEmpty() -> "After $moveLine, focus on central control, piece development, and king safety."
            else -> {
                val top = passages.first()
                val tail = if (passages.size > 1) " ${passages[1].title}: ${sentence(passages[1].text)}" else ""
                "${top.title}: ${sentence(top.text)}$tail"
            }
        }
        return grounded.take(PositionChatValidator.MAX_OUTPUT_CHARS).trim()
    }

    private fun sentence(text: String): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        val first = compact.substringBefore('.').trim().take(125)
        return if (first.lastOrNull() in setOf('!', '?', '.')) first else "$first."
    }

    companion object {
        const val ID = "template-chat-v1"
    }
}

private fun String.splitIntoChunks(): List<String> {
    if (isEmpty()) return emptyList()
    // Split on word boundaries into ~24-char chunks; keeps tests deterministic without re-implementing
    // a real tokenizer (the provider path emits real tokens, this only paces the template fallback).
    val words = this.split(" ")
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    for (word in words) {
        if (current.isNotEmpty() && current.length + 1 + word.length > 24) {
            chunks.add(current.toString())
            current.setLength(0)
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(word)
    }
    if (current.isNotEmpty()) chunks.add(current.toString())
    return chunks
}

/**
 * Streaming LLM composer for position chat. Builds a grounded prompt, streams the OpenAI-compatible
 * provider response token-by-token, and validates the accumulated text at stream end — emitting a
 * [ChatChunk.Fallback] on validation failure or provider error. The grounding passages are pinned
 * into the prompt on every turn so multi-turn conversation cannot drift off the position.
 */
class LlmChatComposer(
    private val client: StreamingLlmClient,
    private val fallback: TemplateChatComposer = TemplateChatComposer(),
    private val budget: ProviderCostBudget = ProviderCostBudget(0.2, 0.0, 0.0),
) : StreamingChatComposer {
    override fun streamCompose(request: PositionChatRequest, passages: List<Passage>): Flow<ChatChunk> = flow {
        val prompt = userPrompt(request, passages)
        if (prompt.length > MAX_PROVIDER_INPUT_CHARS || !budget.admits(prompt.length, MAX_OUTPUT_TOKENS)) {
            fallback.streamCompose(request, passages).collect { emit(it) }
            return@flow
        }
        val accumulated = StringBuilder()
        try {
            client.streamGenerate(SYSTEM_PROMPT, prompt, request.history, MAX_OUTPUT_TOKENS).collect { delta ->
                if (delta.isNotEmpty()) {
                    accumulated.append(delta)
                    emit(ChatChunk.Token(delta))
                }
            }
        } catch (_: Exception) {
            // Provider failure mid-stream → downgrade to deterministic fallback text.
            fallback.streamCompose(request, passages).collect { emit(it) }
            return@flow
        }
        val valid = PositionChatValidator.validate(accumulated.toString(), passages)
        if (valid != null) {
            emit(ChatChunk.Done(ID))
        } else {
            emit(ChatChunk.Fallback(fallbackText(request, passages)))
        }
    }.flowOn(Dispatchers.IO)

    private fun userPrompt(request: PositionChatRequest, passages: List<Passage>): String = buildString {
        appendLine("ECO: ${request.eco ?: "unknown"}")
        appendLine("Moves: ${request.movesSan.takeLast(12).joinToString(" ")}")
        appendLine("Retrieved sources:")
        passages.forEach { appendLine("[${it.sourceId}] ${it.title}: ${it.text}") }
        appendLine("Player's Question: ${request.userMessage.trim()}")
        append("Answer the player's question in 1-3 short sentences using only these sources. You MUST cite an exact [source-id] (e.g. [source-1]) in EVERY single sentence.")
    }

    /** Validated deterministic body carried by a [ChatChunk.Fallback] when the stream fails. */
    private fun fallbackText(request: PositionChatRequest, passages: List<Passage>): String {
        val moveLine = request.movesSan.takeLast(4).joinToString(" ").ifBlank { "this position" }
        val base = if (passages.isEmpty()) {
            "After $moveLine, focus on central control, piece development, and king safety."
        } else {
            val top = passages.first()
            "${top.title}: ${top.text.take(125)}"
        }
        return base.take(PositionChatValidator.MAX_OUTPUT_CHARS).trim()
    }

    companion object {
        private const val ID = "llm-chat-v1"
        private const val MAX_PROVIDER_INPUT_CHARS = 8_000
        private const val MAX_OUTPUT_TOKENS = 160
        private const val SYSTEM_PROMPT =
            "You are a chess position coach answering the player's question. " +
                "Use only the supplied passages. Do not mention engine depth, ratings, or unsupported claims."
    }
}

/**
 * Streaming provider client. [streamGenerate] emits token deltas for the assistant reply. The
 * history turns let the provider carry multi-turn context; the caller pins the grounding passages
 * into the system/user prompt so they survive every turn.
 */
fun interface StreamingLlmClient {
    fun streamGenerate(
        systemPrompt: String,
        userPrompt: String,
        history: List<ChatTurn>,
        maxOutputTokens: Int,
    ): Flow<String>
}

/**
 * OpenAI-compatible streaming client (z.ai GLM, OpenAI, …). Parses `data:` SSE lines from the
 * provider's `/chat/completions` stream: reads `choices[0].delta.content` and stops on
 * `data: [DONE]`. Cancelling the collecting Job aborts the upstream HTTP stream.
 */
class OpenAiCompatibleStreamingLlmClient(
    private val apiKey: String,
    private val endpoint: URI,
    private val model: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val requestTimeout: java.time.Duration = java.time.Duration.ofSeconds(15),
) : StreamingLlmClient {
    override fun streamGenerate(
        systemPrompt: String,
        userPrompt: String,
        history: List<ChatTurn>,
        maxOutputTokens: Int,
    ): Flow<String> = flow {
        val messages = buildList {
            add(ChatMessage("system", systemPrompt))
            // Bounded history keeps multi-turn context but cannot exceed the provider input budget.
            history.takeLast(MAX_HISTORY_TURNS).forEach { turn ->
                if (turn.role == "user" || turn.role == "assistant") add(ChatMessage(turn.role, turn.content))
            }
            add(ChatMessage("user", userPrompt))
        }
        val payload = json.encodeToString(
            StreamChatRequest(model = model, messages = messages, temperature = 0.2, maxTokens = maxOutputTokens),
        )
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        // Read the upstream SSE lazily from the response input stream; cancelling this flow's
        // collecting Job closes the stream via the `use` block (and the request timeout bounds a
        // runaway provider). A `yield()` between lines gives cancellation a suspension checkpoint.
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) return@flow
        response.body().use { input ->
            input.bufferedReader().use { reader ->
                while (true) {
                    val data = reader.readLine() ?: break
                    if (data == "data: [DONE]" || data.isEmpty()) {
                        if (data == "data: [DONE]") break
                        continue
                    }
                    if (!data.startsWith("data:")) continue
                    val jsonPayload = data.removePrefix("data:").trim()
                    if (jsonPayload.isEmpty() || jsonPayload == "[DONE]") continue
                    val delta = runCatching {
                        json.decodeFromString<StreamChatResponse>(jsonPayload)
                            .choices.firstOrNull()?.delta?.content
                    }.getOrNull()
                    if (!delta.isNullOrEmpty()) {
                        emit(delta)
                        yield()
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    @Serializable
    private data class StreamChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double,
        @SerialName("max_tokens") val maxTokens: Int,
        @SerialName("stream") val stream: Boolean = true,
    )

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class StreamChatResponse(val choices: List<Choice> = emptyList()) {
        @Serializable
        data class Choice(val delta: Delta = Delta())
    }

    @Serializable
    private data class Delta(val content: String? = null)

    private companion object {
        const val MAX_HISTORY_TURNS = 12
    }
}

/**
 * Grounding validator for streamed chat output. Mirrors [OpeningExplanationValidator] but is
 * slightly more permissive (1-3 sentences, not strictly 2-3) since chat answers vary in length.
 * The same forbidden-phrase, citation, and token-overlap rules apply so chat cannot leak engine
 * depth/ratings or make unsupported certainty claims. Returns the trimmed text on success, `null`
 * on any failure (→ the composer emits a [ChatChunk.Fallback]).
 */
object PositionChatValidator {
    const val MAX_OUTPUT_CHARS = 400

    private val forbiddenPhrases = listOf(
        "i think stockfish",
        "probably depth",
        "stockfish thinks",
        "engine depth",
        "elo ",
        "rating of",
    )
    private val citation = Regex("\\[([^]\\s]+)]")
    private val words = Regex("[a-z0-9]+")
    private val unsupportedCertainty = listOf("forced mate", "guaranteed win", "winning by force", "forces checkmate")
    private val stopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "because", "by", "for", "from", "in", "is",
        "it", "of", "on", "or", "that", "the", "this", "to", "with", "your",
    )

    fun validate(rawText: String, passages: List<Passage>): String? {
        val text = rawText.trim()
        if (text.isEmpty() || text.length > MAX_OUTPUT_CHARS) return null
        val lower = text.lowercase()
        if (forbiddenPhrases.any(lower::contains)) return null
        val byId = passages.associateBy(Passage::sourceId)
        if (byId.isEmpty()) return null
        val sentences = text.split(Regex("(?<=[^0-9][.!?])\\s+(?=[A-Z\"'])")).filter(String::isNotBlank)
        if (sentences.isEmpty() || sentences.size > 4) return null
        if (sentences.any { sentence ->
                val cited = citation.findAll(sentence).map { it.groupValues[1] }.toList()
                if (cited.isEmpty() || cited.any { it !in byId }) return@any true
                val sourceText = cited.joinToString(" ") { id ->
                    byId.getValue(id).let { "${it.title} ${it.text}" }
                }.lowercase()
                val sourceTokens = words.findAll(sourceText).map { it.value }.filter { it !in stopWords }.toSet()
                val claimTokens = words.findAll(sentence.lowercase())
                    .map { it.value }
                    .filter { it.length >= 4 && it !in stopWords }
                    .filterNot { token -> cited.any { id -> token in id.lowercase() } }
                    .toSet()
                claimTokens.intersect(sourceTokens).size < 2 ||
                    unsupportedCertainty.any { phrase -> phrase in sentence.lowercase() && phrase !in sourceText }
            }) return null
        return text
    }
}

/**
 * Orchestrator for the position-chat stream, mirroring [OpeningService] but for a streaming
 * reply. Validates the request (same FEN/SAN/ECO/locale rules), re-pins retrieval on every turn so
 * grounding never drops out of multi-turn context, then delegates to the [streamingChatComposer].
 *
 * The blocking retrieval runs eagerly (within [chat], on the IO dispatcher); the *generation* stream
 * the route collects afterwards is lazy and cancellable — cancelling the collecting Job aborts the
 * provider stream without orphaning the retrieval work that already completed.
 */
class PositionChatService(
    private val dependencies: ChatServerDependencies,
) {
    suspend fun chat(request: PositionChatRequest): Flow<ChatChunk> {
        val passages = withContext(Dispatchers.IO) {
            validateRequest(request)
            val embedding = dependencies.embedder.embed(PositionChatQueryBuilder.build(request))
            require(embedding.size == OpeningService.EMBEDDING_DIMENSIONS) {
                "embedder returned ${embedding.size} values; expected ${OpeningService.EMBEDDING_DIMENSIONS}"
            }
            dependencies.passageRepository.retrieve(embedding, RETRIEVAL_LIMIT)
        }
        return dependencies.streamingChatComposer.streamCompose(request, passages)
    }

    private fun validateRequest(request: PositionChatRequest) {
        require(request.fen.isNotBlank() && request.fen.length <= MAX_FEN_LENGTH && FEN.matches(request.fen)) {
            "fen is malformed or too long"
        }
        require(request.movesSan.size <= MAX_MOVES) { "movesSan must contain at most $MAX_MOVES entries" }
        require(request.movesSan.all { it.length <= MAX_SAN_LENGTH && SAN.matches(it) }) {
            "movesSan contains a malformed move"
        }
        require(request.history.size <= MAX_HISTORY) { "history must contain at most $MAX_HISTORY turns" }
        require(request.history.all { (it.role == "user" || it.role == "assistant") && it.content.length <= MAX_TURN_CHARS }) {
            "history contains an invalid turn"
        }
        require(request.userMessage.isNotBlank() && request.userMessage.length <= MAX_USER_MESSAGE_CHARS) {
            "userMessage is missing or too long"
        }
        val eco = request.eco
        require(eco == null || ECO.matches(eco)) { "eco must be a valid ECO code" }
        val locale = request.locale
        require(locale == null || (locale.length <= MAX_LOCALE_LENGTH && LOCALE.matches(locale))) {
            "locale is malformed or too long"
        }
    }

    companion object {
        private const val RETRIEVAL_LIMIT = 4
        private const val MAX_MOVES = 20
        private const val MAX_FEN_LENGTH = 128
        private const val MAX_SAN_LENGTH = 16
        private const val MAX_LOCALE_LENGTH = 64
        private const val MAX_HISTORY = 12
        private const val MAX_TURN_CHARS = 500
        private const val MAX_USER_MESSAGE_CHARS = 500
        private val FEN = Regex("[prnbqkPRNBQK1-8/ wbKQkq\\-a-h0-9]+")
        private val SAN = Regex("[KQRBNOa-hx1-8=+#-]+")
        private val ECO = Regex("[A-E][0-9]{2}[a-z]?")
        private val LOCALE = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
    }
}

object PositionChatQueryBuilder {
    private const val MAX_OPENING_MOVES = 12

    fun build(request: PositionChatRequest): String = buildString {
        request.eco?.takeIf(String::isNotBlank)?.let { append("ECO ").append(it.trim()).append(' ') }
        append("opening after ")
        append(request.movesSan.take(MAX_OPENING_MOVES).joinToString(" ").ifBlank { "the supplied position" })
        append(' ')
        // Append the user's question so retrieval is anchored to both the position and the query —
        // the same MiniLM embed step the opening route uses, now question-aware.
        append(request.userMessage.take(MAX_QUERY_CHARS).trim())
    }

    private const val MAX_QUERY_CHARS = 200
}

/**
 * Server dependencies for the chat route. Shares [embedder] and [passageRepository] with the
 * opening-explainer route (same retrieval index); the chat composer is distinct.
 */
data class ChatServerDependencies(
    val embedder: Embedder,
    val passageRepository: PassageRepository,
    val streamingChatComposer: StreamingChatComposer,
)
