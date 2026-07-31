package com.example.ondeviceai

import com.example.coachapi.ChatStreamEvent
import com.example.coachapi.PositionChatRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultPositionChatTest {
    private val request = PositionChatRequest(
        fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
        movesSan = listOf("e4", "e5"),
        eco = "C20",
        userMessage = "What is this opening?",
    )

    private fun cloudContext() = AiContextSnapshot(
        availableLocalVendors = emptyList(),
        isNetworkAvailable = true,
        isAppForegrounded = true,
        userSetting = AiUserSetting.ALLOW_CLOUD,
    )

    @Test
    fun `forwards cloud token and done events when route is cloud`() = runTest {
        val chat = DefaultPositionChat(
            client = StreamingChatClient {
                flowOf(
                    ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "The center "),
                    ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "is contested."),
                    ChatStreamEvent(ChatStreamEvent.TYPE_DONE, composerId = "llm-chat-v1"),
                )
            },
            contextProvider = ::cloudContext,
        )

        val events = chat.stream(request).toList()

        assertEquals(3, events.size)
        assertEquals("token", events[0].type)
        assertEquals("done", events.last().type)
    }

    @Test
    fun `null client with cloud route emits a single fallback event`() = runTest {
        val chat = DefaultPositionChat(
            client = null,
            contextProvider = ::cloudContext,
        )

        val events = chat.stream(request).toList()

        assertEquals(1, events.size)
        assertEquals("fallback", events.single().type)
    }

    @Test
    fun `provider error mid-stream downgrades to a fallback event`() = runTest {
        val chat = DefaultPositionChat(
            client = StreamingChatClient {
                flow {
                    emit(ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "partial…"))
                    throw RuntimeException("provider exploded")
                }
            },
            contextProvider = ::cloudContext,
        )

        val events = chat.stream(request).toList()

        // One partial token forwarded, then the error → fallback.
        assertEquals(2, events.size)
        assertEquals("token", events[0].type)
        assertEquals("fallback", events[1].type)
    }

    @Test
    fun `no network routes to a fallback event without calling the client`() = runTest {
        var clientCalled = false
        val chat = DefaultPositionChat(
            client = StreamingChatClient {
                clientCalled = true
                flowOf(ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "should not happen"))
            },
            contextProvider = {
                cloudContext().copy(isNetworkAvailable = false)
            },
        )

        val events = chat.stream(request).toList()

        assertEquals(1, events.size)
        assertEquals("fallback", events.single().type)
        assertEquals(false, clientCalled)
    }
}
