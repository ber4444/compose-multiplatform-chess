package com.example.myapplication.chat

import com.example.coachapi.ChatStreamEvent
import com.example.coachapi.PositionChatRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [KtorStreamingChatClient] parses the SSE wire format emitted by the server route
 * (`data: <json>\n\n` lines → [ChatStreamEvent]s) and stops at a terminal event. Uses a [MockEngine]
 * that returns a hand-built SSE body — no network.
 */
class KtorStreamingChatClientTest {
    private val json = Json { encodeDefaults = true }

    private fun sse(vararg events: ChatStreamEvent): String =
        events.joinToString("\n\n", postfix = "\n\n") {
            "data: ${json.encodeToString(it)}"
        }

    private fun client(events: List<ChatStreamEvent>): KtorStreamingChatClient {
        val engine = MockEngine { request ->
            assertEquals("/v1/positions/chat/stream", request.url.encodedPath)
            respond(
                content = sse(*events.toTypedArray()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        return KtorStreamingChatClient(
            HttpClient(engine) { install(ContentNegotiation) { json() } },
            "https://coach.test",
        )
    }

    private val request = PositionChatRequest(
        fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
        movesSan = listOf("e4", "e5"),
        userMessage = "What is this?",
    )

    @Test
    fun `parses token events then stops at done`() = runTest {
        val chat = client(
            listOf(
                ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "The center "),
                ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "is contested."),
                ChatStreamEvent(ChatStreamEvent.TYPE_DONE, composerId = "llm-chat-v1"),
            ),
        )

        val events = chat.stream(request).toList()

        assertEquals(3, events.size)
        assertEquals("token", events[0].type)
        assertEquals("The center ", events[0].text)
        assertEquals("done", events.last().type)
    }

    @Test
    fun `parses a fallback terminal event`() = runTest {
        val chat = client(
            listOf(
                ChatStreamEvent(ChatStreamEvent.TYPE_FALLBACK, text = "Focus on the center."),
            ),
        )

        val events = chat.stream(request).toList()

        assertEquals(1, events.size)
        assertEquals("fallback", events.single().type)
        assertEquals("Focus on the center.", events.single().text)
    }

    @Test
    fun `non successful status yields no events`() = runTest {
        val engine = MockEngine { respond("nope", HttpStatusCode.ServiceUnavailable) }
        val chat = KtorStreamingChatClient(
            HttpClient(engine) { install(ContentNegotiation) { json() } },
            "https://coach.test",
        )

        val events = chat.stream(request).toList()

        assertEquals(0, events.size)
    }
}
