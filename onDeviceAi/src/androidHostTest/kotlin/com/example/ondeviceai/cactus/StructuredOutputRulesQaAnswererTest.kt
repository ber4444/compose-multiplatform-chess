package com.example.ondeviceai.cactus

import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.FakeTextGenerator
import com.example.ondeviceai.FakeVendorRouteExecutor
import com.example.ondeviceai.RuleLookupTool
import com.example.ondeviceai.RulePassage
import com.example.ondeviceai.VendorRoute
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuredOutputRulesQaAnswererTest {

    @Test
    fun `runs lookup envelope then grounded second turn`() = runTest {
        val prompts = mutableListOf<String>()
        val generator = FakeTextGenerator(
            generateInterceptor = { request, call ->
                prompts += request.userPrompt
                if (call == 1) {
                    """{"tool":"lookup_rule","query":"castle through check"}"""
                } else {
                    "A king may not castle through check. [castling-check]"
                }
            },
        )
        var lookupQuery = ""
        val lookup = RuleLookupTool { query ->
            lookupQuery = query
            listOf(
                RulePassage(
                    id = "castling-check",
                    title = "Castling and attacked squares",
                    text = "Castling is illegal while the king is in check or through check.",
                ),
            )
        }

        val output = StructuredOutputRulesQaAnswerer(
            executor = FakeVendorRouteExecutor(generator),
            lookupTool = lookup,
        ).answer("May I castle through check?", VendorRoute.CactusLocal())

        assertEquals("castle through check", lookupQuery)
        assertEquals(listOf("castling-check"), output.retrievedPassageIds)
        assertTrue(output.text.contains("[castling-check]"))
        assertTrue(prompts.first().contains("structured-output", ignoreCase = true))
        assertTrue(prompts.last().contains("castling-check"))
        assertEquals(2, generator.generateCount)
        assertEquals(1, generator.closeCount)
    }

    @Test
    fun `malformed envelope returns ungrounded output for shared validator fallback`() = runTest {
        val generator = FakeTextGenerator(
            status = AiAvailability.Available,
            response = "I think castling is probably allowed.",
        )

        val output = StructuredOutputRulesQaAnswerer(
            executor = FakeVendorRouteExecutor(generator),
            lookupTool = RuleLookupTool { emptyList() },
        ).answer("May I castle through check?", VendorRoute.CactusLocal())

        assertEquals(emptyList(), output.retrievedPassageIds)
        assertEquals("I think castling is probably allowed.", output.text)
        assertEquals(1, generator.generateCount)
    }
}
