package com.example.coachserver

import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.Passage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LlmComposer + OpenAiCompatibleLlmClient tests backed by a fake HTTP "engine" (an
 * [LlmHttpTransport] lambda). These exercise the full provider-shaped pipeline: request
 * serialization → HTTP transport → response deserialization → validation → fallback. No mocking
 * library; the lambda IS the fake engine.
 *
 * Covers four scenarios:
 *  (a) success — validated LLM prose returns composerId `llm-v1`
 *  (b) validation-failure — ungrounded/forbidden LLM prose falls back to `template-v1`
 *  (c) budget-exceeded — the cost ceiling is enforced before the HTTP call is made
 *  (d) missing-env — selectComposer returns the template when COACH_LLM_API_KEY is absent
 */
class LlmComposerHttpTest {

    private val passages = listOf(
        Passage("c20", "King's Pawn Game", "Both king pawns contest the center and open lines for development."),
        Passage("center", "Central Control", "Central pawns control key squares and support piece development."),
    )
    private val request = OpeningExplainRequest(
        fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
        movesSan = listOf("e4", "e5"),
        eco = "C20",
    )

    // A grounded response that passes OpeningExplanationValidator: 2 sentences, each citing a
    // real source-id, with enough content-word overlap against the source text.
    private val groundedLlmResponse = buildJsonObject(
        "The king pawns contest the center and open development lines [c20]. " +
            "Central control supports early piece development toward key squares [center]."
    )

    // (a) Success — the LLM returns grounded, validated prose; composerId is llm-v1.
    @Test
    fun `llm response that passes validation returns the llm-v1 composer`() {
        var sentBody: String? = null
        val client = OpenAiCompatibleLlmClient.forTesting(
            model = "gpt-4.1-mini",
            transport = LlmHttpTransport { body ->
                sentBody = body
                groundedLlmResponse
            },
        )
        val composer = LlmComposer(client = client, fallback = TemplateComposer())

        val result = composer.compose(request, passages)

        assertEquals("llm-v1", result.composerId)
        assertTrue(result.text.contains("king pawns contest the center"))
        // The fake engine received a serialized OpenAI-compatible chat request.
        val sent = sentBody!!
        assertTrue(sent.contains("\"model\":\"gpt-4.1-mini\""))
        assertTrue(sent.contains("\"max_tokens\""))
        assertTrue(sent.contains("King's Pawn Game"))
    }

    @Test
    fun acceptedProviderTextIsRetained() {
        val client = OpenAiCompatibleLlmClient.forTesting(
            transport = LlmHttpTransport { groundedLlmResponse },
        )
        val result = LlmComposer(client, TemplateComposer()).compose(request, passages)
        
        val expectedText = "The king pawns contest the center and open development lines [c20]. " +
            "Central control supports early piece development toward key squares [center]."
        assertEquals(expectedText, result.rawProviderOutput)
    }

    // (b) Validation failure — the LLM returns forbidden-phrase prose; falls back to template.
    @Test
    fun `llm response with a forbidden phrase falls back to the template`() {
        val forbiddenResponse = buildJsonObject(
            "I think Stockfish probably depth 30 likes this position [c20]. " +
                "The center is contested by both king pawns [center]."
        )
        val client = OpenAiCompatibleLlmClient.forTesting(
            transport = LlmHttpTransport { forbiddenResponse },
        )
        val composer = LlmComposer(client = client, fallback = TemplateComposer())

        val result = composer.compose(request, passages)

        assertEquals("template-v1", result.composerId)
    }

    // (b-2) Validation failure — the LLM returns prose without required citations; falls back.
    @Test
    fun `llm response that does not cite a source id falls back to the template`() {
        val uncitedResponse = buildJsonObject(
            "This is a good opening for both sides. Development and center control matter here. " +
                "The position is balanced and playable."
        )
        val client = OpenAiCompatibleLlmClient.forTesting(
            transport = LlmHttpTransport { uncitedResponse },
        )
        val composer = LlmComposer(client = client, fallback = TemplateComposer())

        val result = composer.compose(request, passages)

        assertEquals("template-v1", result.composerId)
    }

    // (c) Budget exceeded — the cost ceiling is checked BEFORE the HTTP transport is invoked.
    @Test
    fun `budget exceeding the cost ceiling falls back without calling the transport`() {
        var transportCalls = 0
        val client = OpenAiCompatibleLlmClient.forTesting(
            transport = LlmHttpTransport { transportCalls++; groundedLlmResponse },
        )
        val composer = LlmComposer(
            client = client,
            fallback = TemplateComposer(),
            // Prices so high that even a tiny prompt exceeds the 0.2¢ ceiling.
            budget = ProviderCostBudget(
                maxUsdCents = 0.2,
                inputUsdPerMillionTokens = 1_000.0,
                outputUsdPerMillionTokens = 1_000.0,
            ),
        )

        val result = composer.compose(request, passages)

        assertEquals("template-v1", result.composerId)
        assertEquals(0, transportCalls)
    }

    // (d) Missing env — when COACH_LLM_API_KEY is absent, selectComposer returns the template
    // (the LlmComposer is never constructed).
    @Test
    fun `selectComposer returns the template when the api key env var is missing`() {
        val result = selectComposer(emptyMap(), TemplateComposer())
        assertEquals("template-v1", (result as TemplateComposer).let { it.compose(request, passages).composerId })
    }

    // (d-2) Missing env — when the key is set but the token prices are missing, still falls back.
    @Test
    fun `selectComposer returns the template when token prices are missing`() {
        val env = mapOf("COACH_LLM_API_KEY" to "sk-test")
        val result = selectComposer(env, TemplateComposer())
        assertEquals("template-v1", result.compose(request, passages).composerId)
    }

    // (d-3) Missing env — negative prices also prevent construction (the budget can't be enforced).
    @Test
    fun `selectComposer returns the template when token prices are negative`() {
        val env = mapOf(
            "COACH_LLM_API_KEY" to "sk-test",
            "COACH_LLM_INPUT_USD_PER_MILLION" to "-1.0",
            "COACH_LLM_OUTPUT_USD_PER_MILLION" to "0.5",
        )
        val result = selectComposer(env, TemplateComposer())
        assertEquals("template-v1", result.compose(request, passages).composerId)
    }
}

/** Wraps [content] in a minimal OpenAI-compatible chat-completions JSON response body. */
private fun buildJsonObject(content: String): String =
    """{"choices":[{"message":{"role":"assistant","content":${escapeJson(content)}}}]}"""

private fun escapeJson(text: String): String =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
