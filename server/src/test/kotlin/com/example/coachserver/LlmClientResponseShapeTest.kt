package com.example.coachserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Provider response shapes that used to be reported as model-quality failures.
 *
 * Every case here produced the identical outcome before: `composerId = template-v1`, no log line,
 * and a scorecard row reading "the LLM composer failed". None of them are about the model's writing.
 */
class LlmClientResponseShapeTest {

    private fun clientReturning(body: String) =
        OpenAiCompatibleLlmClient.forTesting(transport = { body })

    @Test
    fun `a reasoning model that returns no content field is reported, not silently swallowed`() {
        // Observed live on DeepInfra: the model spends its whole budget deliberating and the
        // message object comes back without a `content` key at all. A non-null `content` field made
        // kotlinx.serialization throw MissingFieldException, which the composer caught as a generic
        // failure — so a client-side parse bug looked exactly like a bad answer.
        val client = clientReturning(
            """{"choices":[{"finish_reason":"length","message":{"role":"assistant","reasoning_content":"thinking..."}}]}""",
        )

        val error = runCatching { client.generate("sys", "user", 90) }.exceptionOrNull()
            ?: fail("Expected a descriptive failure, not a silent null")

        assertTrue(
            error.message.orEmpty().contains("finish_reason=length"),
            "The error must name the cause so it can be fixed: ${error.message}",
        )
        assertTrue(error.message.orEmpty().contains("COACH_LLM_MAX_OUTPUT_TOKENS"))
    }

    @Test
    fun `an explicit null content is treated the same as a missing one`() {
        val client = clientReturning("""{"choices":[{"message":{"role":"assistant","content":null}}]}""")

        assertTrue(runCatching { client.generate("sys", "user", 90) }.isFailure)
    }

    @Test
    fun `unknown provider fields do not break decoding`() {
        // Providers add fields freely; the client must tolerate that rather than fail the request.
        val client = clientReturning(
            """{"id":"x","usage":{"total_tokens":9},"choices":[{"index":0,"message":{"role":"assistant","content":"Fine."},"logprobs":null}]}""",
        )

        assertEquals("Fine.", client.generate("sys", "user", 90)?.text)
    }

    @Test
    fun `reported usage is carried out of the client`() {
        // completion_tokens is the only observable that includes reasoning tokens, and
        // ProviderCostBudget.expectedOutputTokens is calibrated from it. Dropping it during
        // decoding would leave the budget's central constant permanently unmeasurable.
        val client = clientReturning(
            """{"usage":{"prompt_tokens":412,"completion_tokens":733},""" +
                """"choices":[{"message":{"role":"assistant","content":"Fine."}}]}""",
        )

        assertEquals(733, client.generate("sys", "user", 2048)?.completionTokens)
    }

    @Test
    fun `reasoning tokens outside completion_tokens are still counted as billed output`() {
        // Gemini, measured 2026-08-05: the deliberation lands in total_tokens and nowhere else, so
        // reading completion_tokens alone prices the dominant cost component at zero.
        val client = clientReturning(
            """{"usage":{"prompt_tokens":400,"completion_tokens":0,"total_tokens":1600},""" +
                """"choices":[{"message":{"role":"assistant","content":"Fine."}}]}""",
        )

        assertEquals(1_200, client.generate("sys", "user", 2048)?.completionTokens)
    }

    @Test
    fun `a provider that reports no usage still returns its text`() {
        val client = clientReturning("""{"choices":[{"message":{"role":"assistant","content":"Fine."}}]}""")

        val completion = client.generate("sys", "user", 2048)

        assertEquals("Fine.", completion?.text)
        assertEquals(null, completion?.completionTokens)
    }

    @Test
    fun `the token budget is large enough for deliberation, not just the visible answer`() {
        // 90 tokens was derived from the 300-character output cap — the wrong quantity. The chat
        // composer already learned this and sits at 2048; this pins the opening route to the same
        // lesson so the two cannot silently diverge again.
        assertTrue(
            LlmComposer.DEFAULT_MAX_OUTPUT_TOKENS >= 1024,
            "A reasoning model needs headroom before its first visible token; " +
                "got ${LlmComposer.DEFAULT_MAX_OUTPUT_TOKENS}",
        )
        assertEquals(LlmChatComposer.DEFAULT_MAX_OUTPUT_TOKENS, LlmComposer.DEFAULT_MAX_OUTPUT_TOKENS)
    }

    @Test
    fun `a non-2xx response names the status instead of returning null`() {
        val client = OpenAiCompatibleLlmClient.forTesting(transport = { error("HTTP 401 Unauthorized") })

        val error = runCatching { client.generate("sys", "user", 90) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("401"))
    }
}
