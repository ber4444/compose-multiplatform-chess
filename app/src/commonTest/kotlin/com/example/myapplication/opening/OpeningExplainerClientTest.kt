package com.example.myapplication.opening

import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.OpeningExplainResponse
import com.example.coachapi.Passage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpeningExplainerClientTest {
    @Test
    fun `posts wire request and decodes response`() = runTest {
        val expected = OpeningExplainResponse(
            "A central opening.",
            listOf(Passage("c20", "King's Pawn Game", "A central opening.")),
            "template-v1",
        )
        val engine = MockEngine { request ->
            assertEquals("/v1/openings/explain", request.url.encodedPath)
            respond(
                content = Json.encodeToString(expected),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = configuredClient(engine)

        val actual = KtorOpeningExplainerClient(client, "https://coach.test")
            .explain(OpeningExplainRequest("fen", listOf("e4")))

        assertEquals(expected, actual)
    }

    @Test
    fun `non successful response throws a typed error`() = runTest {
        val engine = MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) }
        val client = configuredClient(engine)

        val error = assertFailsWith<OpeningExplainerHttpException> {
            KtorOpeningExplainerClient(client, "https://coach.test")
                .explain(OpeningExplainRequest("fen", emptyList()))
        }

        assertEquals(503, error.statusCode)
    }

    private fun configuredClient(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json() }
    }
}
