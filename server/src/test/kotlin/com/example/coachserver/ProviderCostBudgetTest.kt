package com.example.coachserver

import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.Passage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cost gate, pinned against the *documented defaults*.
 *
 * These exist because the gate previously priced [LlmComposer.DEFAULT_MAX_OUTPUT_TOKENS] — the
 * ceiling — on every call, so at the README's own default cap (0.2c) and the README's own example
 * prices, **every** request was rejected before the network and the composer silently served
 * template text. No test noticed, because the two budget tests that existed only asserted that an
 * absurdly expensive request is rejected: the gate had never been observed *admitting* anything.
 *
 * A criterion never observed passing is as untested as one never observed failing.
 */
class ProviderCostBudgetTest {

    // README step 9's worked example: gpt-4.1-mini pricing, default cap.
    private val documentedDefault = ProviderCostBudget(
        maxUsdCents = DOCUMENTED_MAX_USD_CENTS,
        inputUsdPerMillionTokens = 0.40,
        outputUsdPerMillionTokens = 1.60,
    )

    private val passages = listOf(
        Passage(
            sourceId = "lichess-b-373-b20",
            title = "Sicilian Defense",
            text = "Black answers 1.e4 with 1...c5, fighting for the center asymmetrically and " +
                "opening the c-file for counterplay against White's queenside.",
        ),
        Passage(
            sourceId = "lichess-b-374-b21",
            title = "Smith-Morra Gambit",
            text = "White offers a pawn for rapid development and open lines toward the center.",
        ),
    )

    private val realisticRequest = OpeningExplainRequest(
        fen = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
        movesSan = listOf("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "a6"),
        eco = "B90",
        locale = "en-US",
    )

    @Test
    fun `a realistic opening request is admitted at the documented default cap`() {
        val promptChars = LlmComposer.exampleOutputFor(realisticRequest, passages).length +
            REALISTIC_PROMPT_CHARS

        assertTrue(
            documentedDefault.admits(promptChars, LlmComposer.DEFAULT_MAX_OUTPUT_TOKENS),
            "A request sized like production must be admitted at the documented default, or the " +
                "documented default disables the composer. Expected cost: " +
                documentedDefault.estimateUsdCents(
                    promptChars,
                    ProviderCostBudget.DEFAULT_EXPECTED_OUTPUT_TOKENS,
                ) + "c against a ${DOCUMENTED_MAX_USD_CENTS}c cap",
        )
    }

    @Test
    fun `a realistic chat request is admitted at the documented default cap`() {
        assertTrue(
            documentedDefault.admits(REALISTIC_PROMPT_CHARS, LlmChatComposer.DEFAULT_MAX_OUTPUT_TOKENS),
        )
    }

    @Test
    fun `the composer actually calls the provider at the documented default`() {
        // The end-to-end statement of the same fact: no amount of correct arithmetic helps if the
        // wired-up composer still skips the call.
        var transportCalls = 0
        val composer = LlmComposer(
            client = OpenAiCompatibleLlmClient.forTesting(
                transport = {
                    transportCalls++
                    """{"choices":[{"message":{"role":"assistant","content":""" +
                        """"Black fights for the center asymmetrically with counterplay [lichess-b-373-b20]. """ +
                        """White offers rapid development and open lines toward the center [lichess-b-374-b21]."}}]}"""
                },
            ),
            fallback = TemplateComposer(),
            budget = documentedDefault,
        )

        val result = composer.compose(realisticRequest, passages)

        assertEquals(1, transportCalls, "the documented default must permit the call")
        assertEquals(LlmComposer.ID, result.composerId)
    }

    @Test
    fun `the measured gemini pricing is admitted at the documented default`() {
        // The configuration the 2026-08-05 run actually used: gemini-3.6-flash at 1.50/7.50 per
        // million, ~1400 billed output tokens per call. If the default cap cannot afford this, the
        // shipped default silently disables the composer for a mainstream thinking model.
        val gemini = documentedDefault.copy(
            inputUsdPerMillionTokens = 1.50,
            outputUsdPerMillionTokens = 7.50,
        )

        assertTrue(gemini.admits(REALISTIC_PROMPT_CHARS, LlmComposer.DEFAULT_MAX_OUTPUT_TOKENS))
    }

    @Test
    fun `an absurd token ceiling is refused by the worst-case hard stop`() {
        // The ceiling is no longer priced per request, so something still has to refuse a
        // configuration whose worst case is out of all proportion to the budget.
        assertFalse(documentedDefault.admits(REALISTIC_PROMPT_CHARS, maxOutputTokens = 1_000_000))
    }

    @Test
    fun `a frontier-priced model is refused at the default cap`() {
        val expensive = documentedDefault.copy(
            inputUsdPerMillionTokens = 15.0,
            outputUsdPerMillionTokens = 75.0,
        )

        assertFalse(expensive.admits(REALISTIC_PROMPT_CHARS, LlmComposer.DEFAULT_MAX_OUTPUT_TOKENS))
    }

    @Test
    fun `raising the cap re-admits the expensive model`() {
        // 20c: at 75.00/M output and ~1400 billed tokens, one call genuinely costs ~11.5c. The cap
        // has to be sized against measured usage, which is the whole point of the re-pricing.
        val expensive = documentedDefault.copy(
            maxUsdCents = 20.0,
            inputUsdPerMillionTokens = 15.0,
            outputUsdPerMillionTokens = 75.0,
        )

        assertTrue(expensive.admits(REALISTIC_PROMPT_CHARS, LlmComposer.DEFAULT_MAX_OUTPUT_TOKENS))
    }

    @Test
    fun `expected output is priced below the ceiling, not at it`() {
        val atExpected = documentedDefault.estimateUsdCents(
            REALISTIC_PROMPT_CHARS,
            ProviderCostBudget.DEFAULT_EXPECTED_OUTPUT_TOKENS,
        )
        val atCeiling = documentedDefault.estimateUsdCents(
            REALISTIC_PROMPT_CHARS,
            LlmComposer.DEFAULT_MAX_OUTPUT_TOKENS,
        )

        assertTrue(atExpected < atCeiling, "$atExpected should be below the ceiling estimate $atCeiling")
    }

    private companion object {
        /** The default in [selectComposer]/[selectChatComposer], mirrored here so a change trips this suite. */
        const val DOCUMENTED_MAX_USD_CENTS = 1.5

        /**
         * Prompt size of a real explain call: ECO + 12 SAN plies + up to four retrieved passages +
         * the format instructions. Measured from the live prompt builder, rounded up.
         */
        const val REALISTIC_PROMPT_CHARS = 2_000
    }
}
