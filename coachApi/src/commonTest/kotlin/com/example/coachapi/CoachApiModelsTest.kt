package com.example.coachapi

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CoachApiModelsTest {
    private val json = Json

    @Test
    fun `opening request round trips`() {
        val value = OpeningExplainRequest(
            fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
            movesSan = listOf("e4", "e5"),
            eco = "C20",
            locale = "en-US",
        )

        assertRoundTrip(OpeningExplainRequest.serializer(), value)
    }

    @Test
    fun `passage round trips`() {
        val value = Passage(
            sourceId = "eco-c20",
            title = "King's Pawn Game",
            text = "Both sides contest the center with a king-pawn advance.",
        )

        assertRoundTrip(Passage.serializer(), value)
    }

    @Test
    fun `opening response round trips`() {
        val value = OpeningExplainResponse(
            text = "The opening develops central influence while keeping options flexible.",
            passages = listOf(Passage("eco-c20", "King's Pawn Game", "A central opening.")),
            composerId = "template-v1",
        )

        assertRoundTrip(OpeningExplainResponse.serializer(), value)
    }

    @Test
    fun `api error round trips`() {
        val value = ApiError(code = "invalid_request", message = "fen must not be blank")

        assertRoundTrip(ApiError.serializer(), value)
    }

    private fun <T> assertRoundTrip(serializer: KSerializer<T>, value: T) {
        assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
    }
}
