package com.example.ondeviceai.cactus

import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.AiGenerationRequest
import com.example.ondeviceai.AiRouteExecutor
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.OnDeviceTextGenerator
import com.example.ondeviceai.RuleLookupTool
import com.example.ondeviceai.RulePassage
import com.example.ondeviceai.VendorRoute
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceRulesQaAnswererTest {

    private val lookupTool = mockk<RuleLookupTool>()
    private val executor = mockk<AiRouteExecutor>()
    private val generator = mockk<OnDeviceTextGenerator>()
    private val answerer = OnDeviceRulesQaAnswerer(executor, lookupTool)

    @Test
    fun `test valid json envelope parses correctly`() = runTest {
        val question = "When is a game drawn?"
        val passage = RulePassage("draw", "Draw", "A game is drawn when neither player can win.")
        
        coEvery { lookupTool.lookup(question) } returns listOf(passage)
        coEvery { executor.execute(any()) } returns generator
        coEvery { generator.status() } returns AiAvailability.Available
        every { generator.supportsTools } returns false
        
        // Return valid JSON
        coEvery { generator.generate(match { it.systemPrompt.contains("structured-output") }) } returns flowOf(
            AiTokenOrFinal.Token("{\"tool\":\"lookup_rule\",\"query\":\"draw condition\"}")
        )
        
        coEvery { lookupTool.lookup("draw condition") } returns emptyList()
        
        coEvery { generator.generate(match { it.systemPrompt.contains("Answer the") }) } returns flowOf(
            AiTokenOrFinal.Token("It is a draw [draw].")
        )
        coEvery { generator.close() } returns Unit

        val output = answerer.answer(question, VendorRoute.CactusLocal())

        assertTrue(output.text.contains("It is a draw [draw]."))
        assertEquals(listOf("draw"), output.retrievedPassageIds)
    }

    @Test
    fun `test unparseable refinement keeps question passages and still grounds`() = runTest {
        val question = "When is a game drawn?"
        val passage = RulePassage("draw", "Draw", "A game is drawn when neither player can win.")
        
        coEvery { lookupTool.lookup(question) } returns listOf(passage)
        coEvery { executor.execute(any()) } returns generator
        coEvery { generator.status() } returns AiAvailability.Available
        every { generator.supportsTools } returns false
        
        // Return invalid JSON
        coEvery { generator.generate(match { it.systemPrompt.contains("structured-output") }) } returns flowOf(
            AiTokenOrFinal.Token("{bad json}")
        )
        
        coEvery { generator.generate(match { it.systemPrompt.contains("Answer the") }) } returns flowOf(
            AiTokenOrFinal.Token("It is a draw [draw].")
        )
        coEvery { generator.close() } returns Unit

        val output = answerer.answer(question, VendorRoute.CactusLocal())

        // An unparseable refinement must leave the question's own passages in play -- it may not
        // clear them, and the answer turn still has to produce a grounded answer.
        assertTrue(output.text.contains("It is a draw [draw]."))
        assertEquals(listOf("draw"), output.retrievedPassageIds)
        coVerify(exactly = 1) { lookupTool.lookup(any()) }
    }

    @Test
    fun `test empty model output falls back to the grounded passage text`() = runTest {
        val question = "When is a game drawn?"
        val passage = RulePassage("draw", "Draw", "A game is drawn when neither player can win.")

        coEvery { lookupTool.lookup(question) } returns listOf(passage)
        coEvery { executor.execute(any()) } returns generator
        coEvery { generator.status() } returns AiAvailability.Available
        every { generator.supportsTools } returns false

        coEvery { generator.generate(match { it.systemPrompt.contains("structured-output") }) } returns flowOf(
            AiTokenOrFinal.Token("{\"tool\":\"lookup_rule\",\"query\":\"draw condition\"}")
        )

        coEvery { lookupTool.lookup("draw condition") } returns emptyList()

        // The answer turn says nothing at all.
        coEvery { generator.generate(match { it.systemPrompt.contains("Answer the") }) } returns flowOf(
            AiTokenOrFinal.Token("")
        )
        coEvery { generator.close() } returns Unit

        val output = answerer.answer(question, VendorRoute.CactusLocal())

        // Never RulesQaFallback: retrieval succeeded, so the user gets the rule.
        assertTrue(output.text.contains("A game is drawn when neither player can win."))
        assertEquals(listOf("draw"), output.retrievedPassageIds)
    }

    @Test
    fun `test nested arguments envelope refines the lookup`() = runTest {
        val question = "When is a game drawn?"
        val passage = RulePassage("draw", "Draw", "A game is drawn when neither player can win.")

        coEvery { lookupTool.lookup(question) } returns listOf(passage)
        coEvery { executor.execute(any()) } returns generator
        coEvery { generator.status() } returns AiAvailability.Available
        every { generator.supportsTools } returns false

        // The query lives under `arguments`, the shape a tool-calling model tends to emit.
        coEvery { generator.generate(match { it.systemPrompt.contains("structured-output") }) } returns flowOf(
            AiTokenOrFinal.Token("{\"tool\":\"lookup_rule\",\"arguments\":{\"query\":\"draw condition\"}}")
        )

        val refinedPassage = RulePassage("draw2", "Draw 2", "Another draw rule.")
        coEvery { lookupTool.lookup("draw condition") } returns listOf(refinedPassage)

        coEvery { generator.generate(match { it.systemPrompt.contains("Answer the") }) } returns flowOf(
            AiTokenOrFinal.Token("It is a draw [draw2].")
        )
        coEvery { generator.close() } returns Unit

        val output = answerer.answer(question, VendorRoute.CactusLocal())

        assertTrue(output.text.contains("It is a draw [draw2]."))
        assertEquals(listOf("draw", "draw2"), output.retrievedPassageIds)
    }

    @Test
    fun `test envelope wrapped in prose refines the lookup`() = runTest {
        val question = "When is a game drawn?"
        val passage = RulePassage("draw", "Draw", "A game is drawn when neither player can win.")

        coEvery { lookupTool.lookup(question) } returns listOf(passage)
        coEvery { executor.execute(any()) } returns generator
        coEvery { generator.status() } returns AiAvailability.Available
        every { generator.supportsTools } returns false

        // Leading and trailing prose around the JSON must not defeat the scan.
        coEvery { generator.generate(match { it.systemPrompt.contains("structured-output") }) } returns flowOf(
            AiTokenOrFinal.Token(
                "Sure, here you go: {\"tool\":\"lookup_rule\",\"query\":\"draw condition\"} hope that helps."
            )
        )

        val refinedPassage = RulePassage("draw2", "Draw 2", "Another draw rule.")
        coEvery { lookupTool.lookup("draw condition") } returns listOf(refinedPassage)

        coEvery { generator.generate(match { it.systemPrompt.contains("Answer the") }) } returns flowOf(
            AiTokenOrFinal.Token("It is a draw [draw2].")
        )
        coEvery { generator.close() } returns Unit

        val output = answerer.answer(question, VendorRoute.CactusLocal())

        assertTrue(output.text.contains("It is a draw [draw2]."))
        assertEquals(listOf("draw", "draw2"), output.retrievedPassageIds)
    }

    @Test
    fun `test timeout during generation preserves question passages`() = runTest {
        val question = "When is a game drawn?"
        val passage = RulePassage("draw", "Draw", "A game is drawn when neither player can win.")
        
        coEvery { lookupTool.lookup(question) } returns listOf(passage)
        coEvery { executor.execute(any()) } returns generator
        coEvery { generator.status() } returns AiAvailability.Available
        every { generator.supportsTools } returns false
        
        // Fast JSON
        coEvery { generator.generate(match { it.systemPrompt.contains("structured-output") }) } returns flowOf(
            AiTokenOrFinal.Token("{\"tool\":\"lookup_rule\",\"query\":\"draw condition\"}")
        )
        
        coEvery { lookupTool.lookup("draw condition") } returns emptyList()
        
        // Slow generation that times out
        coEvery { generator.generate(match { it.systemPrompt.contains("Answer the") }) } returns flow {
            delay(30000) // 30s delay to trigger timeout
            emit(AiTokenOrFinal.Token("It is a draw [draw]."))
        }
        coEvery { generator.close() } returns Unit

        val output = answerer.answer(question, VendorRoute.CactusLocal())

        // Should fall back to the ungrounded text built from passages
        assertTrue(output.text.contains("A game is drawn when neither player can win."))
        assertEquals(listOf("draw"), output.retrievedPassageIds)
    }

    @Test
    fun `test native tool calling uses tool results`() = runTest {
        val question = "When is a game drawn?"
        val passage = RulePassage("draw", "Draw", "A game is drawn when neither player can win.")
        
        coEvery { lookupTool.lookup(question) } returns listOf(passage)
        coEvery { executor.execute(any()) } returns generator
        coEvery { generator.status() } returns AiAvailability.Available
        every { generator.supportsTools } returns true
        
        // Return a tool call
        coEvery { generator.generate(match { it.tools.isNotEmpty() }) } returns flowOf(
            AiTokenOrFinal.ToolCall("lookup_rule", mapOf("query" to "draw condition"))
        )
        
        val refinedPassage = RulePassage("draw2", "Draw 2", "Another draw rule.")
        coEvery { lookupTool.lookup("draw condition") } returns listOf(refinedPassage)
        
        coEvery { generator.generate(match { it.systemPrompt.contains("Answer the") }) } returns flowOf(
            AiTokenOrFinal.Token("It is a draw [draw2].")
        )
        coEvery { generator.close() } returns Unit

        val output = answerer.answer(question, VendorRoute.CactusLocal())

        assertTrue(output.text.contains("It is a draw [draw2]."))
        assertEquals(listOf("draw", "draw2"), output.retrievedPassageIds)
    }

    @Test
    fun `test hallucinated id falls back to question passages`() = runTest {
        val question = "When is a game drawn?"
        val passage = RulePassage("draw", "Draw", "A game is drawn when neither player can win.")
        
        coEvery { lookupTool.lookup(question) } returns listOf(passage)
        coEvery { executor.execute(any()) } returns generator
        coEvery { generator.status() } returns AiAvailability.Available
        every { generator.supportsTools } returns false
        
        // Return valid JSON
        coEvery { generator.generate(match { it.systemPrompt.contains("structured-output") }) } returns flowOf(
            AiTokenOrFinal.Token("{\"tool\":\"lookup_rule\",\"query\":\"draw condition\"}")
        )
        
        coEvery { lookupTool.lookup("draw condition") } returns emptyList()
        
        // Return an id that wasn't retrieved
        coEvery { generator.generate(match { it.systemPrompt.contains("Answer the") }) } returns flowOf(
            AiTokenOrFinal.Token("It is a draw [fake-id].")
        )
        coEvery { generator.close() } returns Unit

        val output = answerer.answer(question, VendorRoute.CactusLocal())

        // Hallucinated id causes ungrounded to fail, fallback to passage directly
        assertTrue(output.text.contains("A game is drawn when neither player can win."))
        assertEquals(listOf("draw"), output.retrievedPassageIds)
    }
}
