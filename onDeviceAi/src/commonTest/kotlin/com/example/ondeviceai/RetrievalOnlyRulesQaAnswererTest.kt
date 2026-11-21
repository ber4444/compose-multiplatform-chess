package com.example.ondeviceai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Rules Q&A on a platform with the rulebook but no model, which is the web build: every answer is the
 * best-matching passage, cited, and labelled as reference text rather than model output.
 */
class RetrievalOnlyRulesQaAnswererTest {
    private val lookup = createBundledRuleLookupTool()

    /** What the wasm entry point builds: no local vendor, offline-only. */
    private fun webContext() = AiContextSnapshot(availableLocalVendors = emptyList())

    @Test
    fun `with no model the orchestrator answers from the rulebook and says so`() = runTest {
        val orchestrator = DefaultRulesQaOrchestrator(RetrievalOnlyRulesQaAnswerer(lookup), lookup) { webContext() }

        val result = orchestrator.answer("When is en passant allowed?")

        val success = assertIs<RulesQaResult.Success>(result)
        assertEquals(listOf(RuleCitation("en-passant", "En passant")), success.citations)
        assertTrue("pawn" in success.text, success.text)
        // Provenance: reference text, never presented as on-device model output.
        assertIs<AiRoute.Fallback>(success.route)
    }

    @Test
    fun `if a route ever reaches it the answer is still the cited reference text`() = runTest {
        val output = RetrievalOnlyRulesQaAnswerer(lookup).answer("When is en passant allowed?", VendorRoute.LiteRtLm())

        assertEquals("en-passant", output.retrievedPassageIds.first())
        assertIs<RulesQaResponseValidator.Result.Valid>(RulesQaResponseValidator.validate(output.text, output.retrievedPassageIds))
        assertEquals(RulesQaGrounding.composeFromPassages(output.retrievedPassages), output.text)
    }

    @Test
    fun `a question the rulebook cannot match still gets the safe fallback and never an empty answer`() = runTest {
        val orchestrator = DefaultRulesQaOrchestrator(RetrievalOnlyRulesQaAnswerer { emptyList() }, { emptyList() }) { webContext() }

        val result = orchestrator.answer("What is the capital of France?")

        assertEquals(RulesQaFallback.TEXT, assertIs<RulesQaResult.FellBack>(result).text)
    }
}
