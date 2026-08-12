package com.example.ondeviceai

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DefaultRulesQaOrchestratorTest {

    @Test
    fun `valid locally grounded answer succeeds`() = runTest {
        val orchestrator = DefaultRulesQaOrchestrator(
            answerer = RulesQaAnswerer { _, _ ->
                RulesQaModelOutput(
                    text = "A king may not castle through check. [castling-check]",
                    retrievedPassageIds = listOf("castling-check"),
                )
            },
            lookupTool = { emptyList() },
            contextProvider = { localContext() },
        )

        val result = orchestrator.answer("Can I castle through check?")

        assertIs<RulesQaResult.Success>(result)
        assertEquals(listOf("castling-check"), result.passageIds)
        // The title is resolved from the corpus by id, so the UI never has to print the slug.
        assertEquals(
            listOf(RuleCitation("castling-check", "Castling and attacked squares")),
            result.citations,
        )
    }

    @Test
    fun `a cited id outside the corpus shows itself rather than disappearing`() = runTest {
        val orchestrator = DefaultRulesQaOrchestrator(
            answerer = RulesQaAnswerer { _, _ ->
                RulesQaModelOutput(
                    text = "Grounded in something unknown. [not-in-corpus]",
                    retrievedPassageIds = listOf("not-in-corpus"),
                )
            },
            lookupTool = { emptyList() },
            contextProvider = { localContext() },
        )

        val result = orchestrator.answer("Anything?")

        assertIs<RulesQaResult.Success>(result)
        assertEquals(listOf(RuleCitation("not-in-corpus", "not-in-corpus")), result.citations)
    }

    @Test
    fun `ungrounded answer uses static rules fallback`() = runTest {
        val orchestrator = DefaultRulesQaOrchestrator(
            answerer = RulesQaAnswerer { _, _ ->
                RulesQaModelOutput("Yes, whenever you want.", emptyList())
            },
            lookupTool = { emptyList() },
            contextProvider = { localContext() },
        )

        val result = orchestrator.answer("Can I castle through check?")

        assertIs<RulesQaResult.FellBack>(result)
        assertEquals(RulesQaFallback.TEXT, result.text)
    }

    @Test
    fun `missing local model falls back and never calls answerer`() = runTest {
        var called = false
        val orchestrator = DefaultRulesQaOrchestrator(
            answerer = RulesQaAnswerer { _, _ ->
                called = true
                RulesQaModelOutput("unused", emptyList())
            },
            lookupTool = { emptyList() },
            contextProvider = { localContext(hasModel = false) },
        )

        val result = orchestrator.answer("What is stalemate?")

        assertIs<RulesQaResult.FellBack>(result)
        assertEquals(false, called)
    }

    @Test
    fun `missing local model uses lookupTool fallback when available`() = runTest {
        val orchestrator = DefaultRulesQaOrchestrator(
            answerer = RulesQaAnswerer { _, _ ->
                RulesQaModelOutput("unused", emptyList())
            },
            lookupTool = { listOf(
                RulePassage("stalemate", "Stalemate", "Stalemate text"),
                RulePassage("checkmate", "Checkmate", "Checkmate text")
            ) },
            contextProvider = { localContext(hasModel = false) },
        )

        val result = orchestrator.answer("What is stalemate?")

        assertIs<RulesQaResult.Success>(result)
        assertEquals(listOf("stalemate"), result.passageIds)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // TestScope.currentTime
    @Test
    fun `a hanging answerer is bounded by the latency budget`() = runTest {
        val orchestrator = DefaultRulesQaOrchestrator(
            answerer = RulesQaAnswerer { _, _ -> awaitCancellation() },
            lookupTool = { emptyList() },
            contextProvider = { localContext() },
        )

        // The virtual clock makes this instant; the assertion is that it *terminates* at all. Only
        // the orchestrator can guarantee that — the iOS Foundation Models answerer has no timeout
        // of its own, so removing this one strands the Rules screen in Loading forever.
        val result = orchestrator.answer("What is stalemate?")

        assertIs<RulesQaResult.FellBack>(result)
        assertEquals(AiRoutePolicyDecider.FallbackReason.Timeout, result.reason)
        assertEquals(
            AiRoutePolicies.rulesQaOffline.latencyBudget.completeMs,
            currentTime,
        )
    }

    @Test
    fun `a timed-out answerer still answers from the corpus`() = runTest {
        val orchestrator = DefaultRulesQaOrchestrator(
            answerer = RulesQaAnswerer { _, _ -> awaitCancellation() },
            lookupTool = { listOf(RulePassage("stalemate", "Stalemate", "Stalemate text")) },
            contextProvider = { localContext() },
        )

        // Retrieval is deterministic and cheap, so a slow model costs the model's phrasing and
        // nothing else: the user must not be told the rule could not be found.
        val result = orchestrator.answer("What is stalemate?")

        assertIs<RulesQaResult.Success>(result)
        assertEquals(listOf("stalemate"), result.passageIds)
    }

    @Test
    fun `a throwing answerer still answers from the corpus`() = runTest {
        val orchestrator = DefaultRulesQaOrchestrator(
            answerer = RulesQaAnswerer { _, _ -> error("native generation blew up") },
            lookupTool = { listOf(RulePassage("stalemate", "Stalemate", "Stalemate text")) },
            contextProvider = { localContext() },
        )

        val result = orchestrator.answer("What is stalemate?")

        assertIs<RulesQaResult.Success>(result)
        assertEquals(listOf("stalemate"), result.passageIds)
    }

    @Test
    fun `an unvalidatable answer still answers from the corpus`() = runTest {
        val orchestrator = DefaultRulesQaOrchestrator(
            // No passages on the output at all: the answerer retrieved nothing to hand back, so the
            // floor has to come from the orchestrator's own lookup.
            answerer = RulesQaAnswerer { _, _ -> RulesQaModelOutput("Yes, whenever you want.", emptyList()) },
            lookupTool = { listOf(RulePassage("stalemate", "Stalemate", "Stalemate text")) },
            contextProvider = { localContext() },
        )

        val result = orchestrator.answer("What is stalemate?")

        assertIs<RulesQaResult.Success>(result)
        assertEquals(listOf("stalemate"), result.passageIds)
    }

    private fun localContext(hasModel: Boolean = true) = AiContextSnapshot(
        availableLocalVendors = if (hasModel) listOf(VendorRoute.LiteRtLm()) else emptyList(),
        isAppForegrounded = true,
        isNetworkAvailable = true,
        userSetting = AiUserSetting.PREFER_LOCAL,
    )
}
