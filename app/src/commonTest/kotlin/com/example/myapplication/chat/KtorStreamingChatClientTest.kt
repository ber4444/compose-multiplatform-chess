package com.example.myapplication.chat

import com.example.coachapi.ChatStreamEvent
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [KtorStreamingChatClient]'s SSE parsing via [KtorStreamingChatClient.parseSseBody].
 *
 * Tests call [KtorStreamingChatClient.parseSseBody] directly — an `internal` function that takes
 * the full SSE body as a `String` and returns a list of decoded [ChatStreamEvent]s. This decouples
 * SSE parsing coverage from Ktor's [io.ktor.utils.io.ByteReadChannel] so tests don't hit the
 * [io.ktor.client.engine.mock.MockEngine] + `withTimeout` incompatibility with `runTest`'s virtual
 * clock: the test scheduler auto-advances through `withTimeout`, firing the timeout before the mock
 * channel ever signals readability.
 *
 * The HTTP-transport contract (non-2xx → empty flow, timeout → error event) is verified in
 * production via the end-to-end server tests and live integration; a unit mock for those branches
 * would require injecting a coroutine test clock into the production code, which is not warranted
 * for these simple guard clauses.
 */
class KtorStreamingChatClientTest {
    private val json = KtorStreamingChatClient.EVENT_JSON

    private fun sse(vararg events: ChatStreamEvent): String =
        events.joinToString("\n\n", postfix = "\n\n") {
            "data: ${json.encodeToString(it)}"
        }

    @Test
    fun `parses token events then stops at done`() {
        val body = sse(
            ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "The center "),
            ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "is contested."),
            ChatStreamEvent(ChatStreamEvent.TYPE_DONE, composerId = "llm-chat-v1"),
        )

        val events = KtorStreamingChatClient.parseSseBody(body)

        assertEquals(3, events.size)
        assertEquals("token", events[0].type)
        assertEquals("The center ", events[0].text)
        assertEquals("done", events.last().type)
    }

    @Test
    fun `parses a fallback terminal event`() {
        val body = sse(
            ChatStreamEvent(ChatStreamEvent.TYPE_FALLBACK, text = "Focus on the center."),
        )

        val events = KtorStreamingChatClient.parseSseBody(body)

        assertEquals(1, events.size)
        assertEquals("fallback", events.single().type)
        assertEquals("Focus on the center.", events.single().text)
    }

    @Test
    fun `stops at terminal event even when more lines follow`() {
        val body = sse(
            ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "First."),
            ChatStreamEvent(ChatStreamEvent.TYPE_DONE, composerId = "llm-chat-v1"),
            // Lines after the terminal event must be ignored.
            ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "Should not appear."),
        )

        val events = KtorStreamingChatClient.parseSseBody(body)

        assertEquals(2, events.size)
        assertEquals("done", events.last().type)
    }

    @Test
    fun `ignores non-data lines`() {
        val body = ": comment\nevent: ping\ndata: ${json.encodeToString(ChatStreamEvent(ChatStreamEvent.TYPE_DONE))}\n\n"

        val events = KtorStreamingChatClient.parseSseBody(body)

        assertEquals(1, events.size)
        assertEquals("done", events.single().type)
    }

    @Test
    fun `empty body yields no events`() {
        assertEquals(0, KtorStreamingChatClient.parseSseBody("").size)
    }

    @Test
    fun `non-2xx body (unparseable) yields no events`() {
        // Simulates what the server might return in an error body — no `data:` prefix.
        assertEquals(0, KtorStreamingChatClient.parseSseBody("Service Unavailable").size)
    }
}
