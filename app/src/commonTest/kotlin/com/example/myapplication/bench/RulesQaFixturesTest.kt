package com.example.myapplication.bench

import com.example.ondeviceai.RulesQaAnswerer
import com.example.ondeviceai.RulesQaModelOutput
import com.example.ondeviceai.VendorRoute
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesQaFixturesTest {
    @Test
    fun `a wrong model citation is recorded alongside the independent BM25 answer`() = runTest {
        val fixture = RulesQaFixtures.load(
            """[{"question":"Game is a draw when only kings remain?","expectedPassageId":"draw-dead-position"}]""",
        ).single()
        val row = RulesQaFixtures.measure(
            fixture = fixture,
            vendors = listOf(VendorRoute.AppleFoundationModels()),
            deviceModel = "test device",
            osVersion = "test OS",
            answerer = { record ->
                RulesQaAnswerer { _, _ ->
                    RulesQaModelOutput("Invented reply [unseen-rule].", listOf("draw-dead-position"))
                        .also { record(it, null) }
                }
            },
        )
        val fields = Json.parseToJsonElement(row).jsonObject

        assertEquals("draw-dead-position", fields.getValue("retrievedPassageId").jsonPrimitive.content)
        assertTrue(fields.getValue("retrievalScore").jsonPrimitive.content.toDouble() > 0)
        assertTrue(fields.getValue("groundedAnswer").jsonPrimitive.content.endsWith("[draw-dead-position]"))
        assertEquals("Invented reply [unseen-rule].", fields.getValue("modelAnswer").jsonPrimitive.content)
        assertEquals("unseen-rule", fields.getValue("citedPassageIds").jsonArray.single().jsonPrimitive.content)
        assertEquals("Grounded", fields.getValue("kind").jsonPrimitive.content)
        assertEquals("model output failed validation", fields.getValue("fallbackReason").jsonPrimitive.content)
    }
}
