package com.example.coachserver

import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.Passage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class TemplateComposer : TextComposer {
    override fun compose(request: OpeningExplainRequest, passages: List<Passage>): ComposedText {
        val text = when {
            passages.isEmpty() -> {
                val line = request.movesSan.takeLast(4).joinToString(" ").ifBlank { "this position" }
                "The sequence $line reaches an opening position where central control matters. " +
                    "Develop the minor pieces and prepare king safety before starting an attack."
            }
            passages.size == 1 -> {
                val first = passages.first()
                "${first.title}: ${sentence(first.text)} " +
                    "This position is best understood through center control, development, and king safety."
            }
            else -> {
                val first = passages[0]
                val second = passages[1]
                "${first.title}: ${sentence(first.text)} ${second.title}: ${sentence(second.text)}"
            }
        }.take(OpeningExplanationValidator.MAX_OUTPUT_CHARS).trim()
        return ComposedText(text = text, composerId = ID)
    }

    private fun sentence(text: String): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        val first = compact.substringBefore('.').trim().take(125)
        return if (first.lastOrNull() in setOf('!', '?', '.')) first else "$first."
    }

    companion object {
        const val ID = "template-v1"
    }
}

fun interface LlmClient {
    fun generate(systemPrompt: String, userPrompt: String, maxOutputTokens: Int): String?
}

data class ProviderCostBudget(
    val maxUsdCents: Double,
    val inputUsdPerMillionTokens: Double,
    val outputUsdPerMillionTokens: Double,
) {
    fun admits(inputChars: Int, outputTokens: Int): Boolean {
        // Three chars/token deliberately overestimates normal English token use.
        val inputTokens = (inputChars + 2) / 3
        val estimatedUsd = inputTokens * inputUsdPerMillionTokens / 1_000_000.0 +
            outputTokens * outputUsdPerMillionTokens / 1_000_000.0
        return estimatedUsd * 100.0 <= maxUsdCents
    }
}

class LlmComposer(
    private val client: LlmClient,
    private val fallback: TemplateComposer,
    private val budget: ProviderCostBudget = ProviderCostBudget(0.2, 0.0, 0.0),
) : TextComposer {
    override fun compose(request: OpeningExplainRequest, passages: List<Passage>): ComposedText {
        val prompt = userPrompt(request, passages)
        if (prompt.length > MAX_PROVIDER_INPUT_CHARS || !budget.admits(prompt.length, MAX_OUTPUT_TOKENS)) {
            return fallback.compose(request, passages)
        }
        val candidate = runCatching {
            client.generate(SYSTEM_PROMPT, prompt, MAX_OUTPUT_TOKENS)
        }.getOrNull()
        val valid = candidate?.let { OpeningExplanationValidator.validate(it, passages) }
        return if (valid != null) ComposedText(valid, ID) else fallback.compose(request, passages)
    }

    private fun userPrompt(request: OpeningExplainRequest, passages: List<Passage>): String = buildString {
        appendLine("ECO: ${request.eco ?: "unknown"}")
        appendLine("Moves: ${request.movesSan.takeLast(12).joinToString(" ")}")
        appendLine("Retrieved sources:")
        passages.forEach { appendLine("[${it.sourceId}] ${it.title}: ${it.text}") }
        append("Explain the opening in 2-3 short sentences using only these sources. Cite an exact [source-id] in every sentence.")
    }

    companion object {
        private const val ID = "llm-v1"
        private const val MAX_PROVIDER_INPUT_CHARS = 8_000
        private const val MAX_OUTPUT_TOKENS = 120
        private const val SYSTEM_PROMPT =
            "You are a chess opening coach. Use only the supplied passages. " +
                "Do not mention engine depth, ratings, or unsupported claims."
    }
}

object OpeningExplanationValidator {
    const val MAX_OUTPUT_CHARS = 300

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
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter(String::isNotBlank)
        if (sentences.size !in 2..3) return null
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
 * Pluggable HTTP transport for [OpenAiCompatibleLlmClient]. Takes the serialized JSON request body
 * and returns the response body string. Throws on network/transport failure (the composer catches
 * and falls back). In production this is backed by [java.net.http.HttpClient]; in tests a lambda
 * fake acts as the "engine" — no mocking library needed.
 */
fun interface LlmHttpTransport {
    fun send(requestBody: String): String
}

class OpenAiCompatibleLlmClient(
    private val apiKey: String,
    private val endpoint: URI,
    private val model: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val requestTimeout: java.time.Duration = java.time.Duration.ofSeconds(5),
    private val transport: LlmHttpTransport? = null,
) : LlmClient {
    override fun generate(systemPrompt: String, userPrompt: String, maxOutputTokens: Int): String? {
        val payload = ChatRequest(
            model = model,
            messages = listOf(ChatMessage("system", systemPrompt), ChatMessage("user", userPrompt)),
            temperature = 0.2,
            maxTokens = maxOutputTokens,
        )
        val body = json.encodeToString(payload)
        val responseBody = if (transport != null) {
            transport.send(body)
        } else {
            val request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return null
            response.body()
        }
        return json.decodeFromString<ChatResponse>(responseBody).choices.firstOrNull()?.message?.content
    }

    @Serializable
    data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double,
        @SerialName("max_tokens") val maxTokens: Int,
    )

    @Serializable
    data class ChatMessage(val role: String, val content: String)

    @Serializable
    data class ChatResponse(val choices: List<Choice> = emptyList())

    @Serializable
    data class Choice(val message: ChatMessage)

    companion object {
        /**
         * Builds an [OpenAiCompatibleLlmClient] whose HTTP layer is a pluggable transport, bypassing
         * the real [java.net.http.HttpClient]. Use in tests to inject a fake HTTP "engine" without a
         * mocking library: the lambda receives the serialized request body and returns the response
         * body (or throws).
         */
        fun forTesting(
            model: String = "test-model",
            transport: LlmHttpTransport,
        ): OpenAiCompatibleLlmClient = OpenAiCompatibleLlmClient(
            apiKey = "test-key",
            endpoint = URI("https://test.local/chat/completions"),
            model = model,
            transport = transport,
        )
    }
}
