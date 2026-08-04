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

/**
 * What happened on one [LlmComposer.compose] call. A bare fallback *rate* conflates four unrelated
 * causes — the provider was never called, it errored, it returned nothing, or its output was vetoed
 * — and a benchmark that cannot tell them apart reports the gate that closed rather than the reason.
 */
sealed class ComposeAttempt {
    /** Cost gate refused before any network call: no provider was involved in this fallback. */
    data class BudgetRejected(val promptChars: Int) : ComposeAttempt()
    data class ProviderError(val error: String) : ComposeAttempt()
    data object ProviderEmpty : ComposeAttempt()
    data class ValidatorRejected(val reason: String, val raw: String) : ComposeAttempt()
    data object Accepted : ComposeAttempt()

    val label: String
        get() = when (this) {
            is BudgetRejected -> "budget-rejected"
            is ProviderError -> "provider-error"
            ProviderEmpty -> "provider-empty"
            is ValidatorRejected -> "validator-rejected"
            Accepted -> "accepted"
        }
}

class LlmComposer(
    private val client: LlmClient,
    private val fallback: TemplateComposer,
    private val budget: ProviderCostBudget = ProviderCostBudget(0.2, 0.0, 0.0),
    /** No-op in production; the eval harness passes a collector so a run can explain itself. */
    private val probe: (ComposeAttempt) -> Unit = {},
    /**
     * Must cover **reasoning** tokens, not just the ~75 the visible answer needs. This is the same
     * lesson [LlmChatComposer] already learned and documented — a thinking model spends most of its
     * budget deliberating and, at 90, returned either nothing or a two-character fragment like
     * `` ]`). `` that the validator then rejected for sentence count. The old 90 was derived from
     * the 300-character *output* cap, which is the wrong quantity entirely.
     * Override via `COACH_LLM_MAX_OUTPUT_TOKENS`.
     */
    private val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
) : TextComposer {
    override fun compose(request: OpeningExplainRequest, passages: List<Passage>): ComposedText {
        val prompt = userPrompt(request, passages)
        if (prompt.length > MAX_PROVIDER_INPUT_CHARS || !budget.admits(prompt.length, maxOutputTokens)) {
            probe(ComposeAttempt.BudgetRejected(prompt.length))
            return fallback.compose(request, passages)
        }
        // The exception is captured rather than discarded: an auth failure, a bad model id and a
        // model that simply wrote badly all produced the identical "fell back" row before this.
        val attempt = runCatching { client.generate(SYSTEM_PROMPT, prompt, maxOutputTokens) }
        // Strip structurally-marked deliberation before validating. LlmChatComposer already did
        // this for its stream; without it here, a model's scratchpad reached the validator and the
        // rejection was recorded as a quality failure.
        val candidate = attempt.getOrNull()?.let(ModelOutputCleaner::clean)
        val valid = candidate?.let { OpeningExplanationValidator.validate(it, passages) }
        probe(
            when {
                attempt.isFailure -> ComposeAttempt.ProviderError(
                    attempt.exceptionOrNull()!!.let { "${it::class.simpleName}: ${it.message?.take(300)}" },
                )
                candidate == null -> ComposeAttempt.ProviderEmpty
                valid == null -> ComposeAttempt.ValidatorRejected(
                    reason = OpeningExplanationValidator.rejectionReason(candidate, passages).orEmpty(),
                    raw = candidate,
                )
                else -> ComposeAttempt.Accepted
            },
        )
        return if (valid != null) ComposedText(valid, ID) else fallback.compose(request, passages)
    }

    private fun userPrompt(request: OpeningExplainRequest, passages: List<Passage>): String = buildString {
        appendLine("ECO: ${request.eco ?: "unknown"}")
        appendLine("Moves: ${request.movesSan.takeLast(12).joinToString(" ")}")
        appendLine("Retrieved sources (cite these by their bracketed id):")
        passages.forEach { appendLine("[${it.sourceId}] ${it.title}: ${it.text}") }
        appendLine()
        appendLine("Write EXACTLY 2 or 3 sentences (no more, no less). Total length under 280 characters.")
        appendLine("Every sentence MUST end with a bracketed source id like [${passages.first().sourceId}].")
        appendLine("Use ONLY facts from the sources above. Do not invent moves, evaluations, or threats.")
        // Belt to the cleaner's braces: the cleaner only removes deliberation that is structurally
        // marked, so the cheapest fix for unmarked scratchpad is to not ask for it.
        appendLine("Output ONLY the final answer. No reasoning, notes, bullet lists, or corrections.")
        appendLine()
        appendLine("Example of the required format:")
        appendLine(exampleOutputFor(request, passages))
    }

    companion object {
        const val ID = "llm-v1"

        /**
         * The worked example embedded in the prompt. Extracted so a test can assert the thing the
         * model is told to imitate actually passes [OpeningExplanationValidator] — showing a model
         * a non-compliant target and then grading it on compliance produces a fallback rate that
         * says nothing about the model.
         */
        fun exampleOutputFor(request: OpeningExplainRequest, passages: List<Passage>): String =
            passages.first().let { p ->
                val focus = p.text.substringBefore('.').take(60)
                "This opening emphasizes $focus [${p.sourceId}]. " +
                    (passages.getOrNull(1)?.let { q ->
                        val qfocus = q.text.substringBefore('.').take(60)
                        "It also matters because of $qfocus [${q.sourceId}]."
                    } ?: "Piece development and king safety round out the plan [${p.sourceId}].")
            }

        private const val MAX_PROVIDER_INPUT_CHARS = 8_000

        /**
         * Sized for reasoning + answer, not the answer alone. The visible reply needs ~75 tokens;
         * the deliberation before it needs the rest. Mirrors LlmChatComposer.DEFAULT_MAX_OUTPUT_TOKENS.
         */
        const val DEFAULT_MAX_OUTPUT_TOKENS = 2048

        private const val SYSTEM_PROMPT =
            "You are a chess opening coach. You MUST follow the output format exactly: " +
                "2 or 3 sentences, each ending with a bracketed source id like [source-1], " +
                "under 280 characters total. Use ONLY the supplied sources; never invent moves, " +
                "engine evaluations, ratings, or threats. The bracketed id is mandatory in every sentence. " +
                "Reply with the answer only \u2014 no deliberation, self-correction, or commentary."
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

    fun validate(rawText: String, passages: List<Passage>): String? =
        if (rejectionReason(rawText, passages) == null) rawText.trim() else null

    /**
     * Splits prose into sentences **without** treating chess move numbers as boundaries.
     *
     * `"White can respond with 2. Ke2 in King David's Opening [id]."` is one sentence. A naive
     * split on `(?<=[.!?])\s+` reads it as two, and the period inside `1...c5` as two more — so a
     * model that wrote a perfectly compliant three-sentence cited answer was counted at five or
     * seven and rejected for sentence count. Measured live: this rejected *every* well-formed
     * answer the provider produced, which is what the "LLM composer fails 100% of the time" result
     * was actually recording.
     *
     * The masking rule is that a period **preceded by a digit** belongs to move notation, never to
     * a sentence. That is safe under this validator's own contract: every sentence must end with a
     * bracketed source id, so a real boundary is always preceded by `]`, never by a digit.
     *
     * `MoveCoachResponseValidator.splitSentences` has the same hazard noted for decimals ("0.5") on
     * the on-device side; the two validators are separate code and only this one handles it.
     */
    internal fun splitSentences(text: String): List<String> {
        val masked = MOVE_NUMBER.replace(text) { match ->
            match.groupValues[1] + MOVE_DOT.toString().repeat(match.groupValues[2].length)
        }
        return masked.split(Regex("(?<=[.!?])\\s+"))
            .map { it.replace(MOVE_DOT, '.') }
            .filter(String::isNotBlank)
    }

    /** Digit followed by one or more periods: `2.`, `13.`, and the `1...` of a black-move ellipsis. */
    private val MOVE_NUMBER = Regex("(\\d)(\\.+)")

    /**
     * Stand-in for a move-notation period while splitting, restored immediately afterwards.
     * Written as an escape, not a literal control byte: an embedded NUL makes the source file
     * binary to grep and diff tools. It must be a character model output cannot contain — an
     * ordinary one (a space, say) would be substituted back to a period everywhere it occurs.
     */
    private const val MOVE_DOT = '\u0000'

    /**
     * Why [validate] would reject, or `null` if it accepts.
     *
     * Exists because "the LLM route fell back 100% of the time" is not a finding — it is four
     * different findings wearing one number (never called, budget-rejected, provider error,
     * validator veto), and the veto itself is five more. Every caller that reports a fallback rate
     * should be able to say which rule closed.
     */
    fun rejectionReason(rawText: String, passages: List<Passage>): String? {
        val text = rawText.trim()
        if (text.isEmpty()) return "empty"
        if (text.length > MAX_OUTPUT_CHARS) return "length ${text.length} > $MAX_OUTPUT_CHARS"
        val lower = text.lowercase()
        forbiddenPhrases.firstOrNull(lower::contains)?.let { return "forbidden phrase: $it" }
        val byId = passages.associateBy(Passage::sourceId)
        if (byId.isEmpty()) return "no passages to cite"
        val sentences = splitSentences(text)
        if (sentences.size !in 2..3) return "sentence count ${sentences.size}, need 2..3"
        sentences.forEach { sentence ->
            val cited = citation.findAll(sentence).map { it.groupValues[1] }.toList()
            if (cited.isEmpty()) return "uncited sentence: ${sentence.take(60)}"
            cited.firstOrNull { it !in byId }?.let { return "unknown source id [$it]" }
            val sourceText = cited.joinToString(" ") { id ->
                byId.getValue(id).let { "${it.title} ${it.text}" }
            }.lowercase()
            val sourceTokens = words.findAll(sourceText).map { it.value }.filter { it !in stopWords }.toSet()
            val claimTokens = words.findAll(sentence.lowercase())
                .map { it.value }
                .filter { it.length >= 4 && it !in stopWords }
                .filterNot { token -> cited.any { id -> token in id.lowercase() } }
                .toSet()
            val overlap = claimTokens.intersect(sourceTokens)
            if (overlap.size < 2) {
                return "sentence shares only ${overlap.size} content word(s) $overlap with its " +
                    "source, need 2: ${sentence.take(60)}"
            }
            unsupportedCertainty.firstOrNull { it in sentence.lowercase() && it !in sourceText }
                ?.let { return "unsupported certainty: $it" }
        }
        return null
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
            // Throwing rather than returning null is the whole point: LlmComposer catches this and
            // reports it as ProviderError, so a 401 (bad key) and a 404 (wrong model id) are
            // distinguishable from a model that merely wrote badly. Returning null made every one
            // of those a plain "fell back" row.
            if (response.statusCode() !in 200..299) {
                error("provider returned HTTP ${response.statusCode()}: ${response.body().take(200)}")
            }
            response.body()
        }
        val choice = json.decodeFromString<ChatResponse>(responseBody).choices.firstOrNull()
        val content = choice?.message?.content?.takeIf(String::isNotBlank)
        if (content == null) {
            // Distinguish "spent the budget thinking" from "returned nothing" — the first is fixed
            // by raising maxOutputTokens, the second is a provider problem. Both used to look the
            // same from the outside.
            error(
                "provider returned no content (finish_reason=${choice?.finishReason}, " +
                    "reasoning=${choice?.message?.reasoningContent?.length ?: 0} chars). " +
                    "If finish_reason is 'length', raise COACH_LLM_MAX_OUTPUT_TOKENS.",
            )
        }
        return content
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

    /**
     * `content` is **nullable and defaulted**, and that is load-bearing rather than defensive.
     *
     * A reasoning model that exhausts its token budget on deliberation returns
     * `{"role":"assistant"}` with no `content` key at all. Declaring it non-null made
     * `decodeFromString` throw `MissingFieldException`, which `LlmComposer` caught and reported as
     * an ordinary fallback — so a *client-side parse failure* was indistinguishable from the model
     * writing something the validator rejected. Measured against DeepInfra: this was the single
     * largest contributor to the "LLM composer fails 100% of the time" result.
     *
     * `reasoningContent` is read only so a truncated deliberation can be logged; it is never
     * returned to a user, because it is not the model's answer.
     */
    @Serializable
    data class ResponseMessage(
        val role: String = "assistant",
        val content: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null,
    )

    @Serializable
    data class Choice(
        val message: ResponseMessage,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

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
