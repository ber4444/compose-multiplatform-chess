package com.example.myapplication.chat

import com.example.coachapi.ChatStreamEvent
import com.example.coachapi.PositionChatRequest
import com.example.myapplication.GameUiState
import com.example.myapplication.MoveRecord
import com.example.myapplication.WinState
import com.example.ondeviceai.PositionChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * State-machine tests for [ChatViewModel]: stream tokens, Stop mid-stream, Retry after a cancel,
 * and error handling. Uses a fake [PositionChat] so no network is involved.
 *
 * The injected scope uses [UnconfinedTestDispatcher] (sharing the test scheduler) so launched
 * collection runs eagerly — the cold flows in the simple cases complete in one step, and the
 * Stop/Retry cases use a hot [MutableSharedFlow] so the collector suspends at the right moment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    private val gameState = GameUiState(
        winState = WinState.NONE,
        moveHistory = listOf(MoveRecord(uci = "e2e4", san = "e4", fenAfter = "fen")),
    )

    @Test
    fun `send streams tokens then finalizes the assistant message`() = runTest {
        val chat = fakeChat(
            flow {
                emit(ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "The center "))
                emit(ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "is contested [s1]."))
                emit(ChatStreamEvent(ChatStreamEvent.TYPE_DONE, composerId = "llm-chat-v1"))
            },
        )
        val vm = ChatViewModel(chat, scope = testScope())

        vm.send(gameState, "What is this?")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.streaming)
        assertEquals(2, state.messages.size) // user + assistant
        assertEquals("user", state.messages[0].role)
        assertEquals("assistant", state.messages[1].role)
        assertEquals("The center is contested [s1].", state.messages[1].text)
        assertEquals(false, state.messages[1].isFallback)
    }

    @Test
    fun `stop mid-stream cancels and surfaces retry`() = runTest {
        val events = MutableSharedFlow<ChatStreamEvent>(extraBufferCapacity = 8)
        val chat = fakeChat(events.asSharedFlow())
        val vm = ChatViewModel(chat, scope = testScope())

        vm.send(gameState, "Tell me about the center")
        advanceUntilIdle()
        // Emit one token then stop before the stream completes.
        events.emit(ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "partial…"))
        advanceUntilIdle()
        assertEquals(true, vm.state.value.streaming)
        assertEquals("partial…", vm.state.value.partialText)

        vm.stop()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.streaming)
        assertEquals(true, state.canRetry)
        // The partial assistant text is promoted to a marked fallback message.
        assertEquals(true, state.messages.any { it.role == "assistant" && it.isFallback })
    }

    @Test
    fun `retry re-issues the last turn and clears the failed message`() = runTest {
        val firstFlow = MutableSharedFlow<ChatStreamEvent>(extraBufferCapacity = 8)
        var streamInvocation = 0
        var currentFlow: Flow<ChatStreamEvent> = firstFlow.asSharedFlow()
        val chat = object : PositionChat {
            override fun stream(request: PositionChatRequest): Flow<ChatStreamEvent> {
                streamInvocation++
                return currentFlow
            }
            override fun close() = Unit
        }
        val vm = ChatViewModel(chat, scope = testScope())

        vm.send(gameState, "Why e5?")
        advanceUntilIdle()
        firstFlow.emit(ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "x"))
        advanceUntilIdle()
        vm.stop()
        advanceUntilIdle()

        // Second stream (the retry) completes successfully.
        currentFlow = flow {
            emit(ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "Because it contests the center [s1]."))
            emit(ChatStreamEvent(ChatStreamEvent.TYPE_DONE, composerId = "llm-chat-v1"))
        }
        vm.retry(gameState)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.streaming)
        assertEquals(false, state.canRetry)
        // The failed/cancelled assistant message was dropped; only the user turn + fresh reply remain.
        assertEquals(2, state.messages.size)
        assertEquals("assistant", state.messages[1].role)
        assertEquals(false, state.messages[1].isFallback)
        assertEquals(2, streamInvocation)
    }

    @Test
    fun `fallback event replaces streamed partial with the fallback text`() = runTest {
        val chat = fakeChat(
            flow {
                emit(ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "I think Stockfish…"))
                emit(
                    ChatStreamEvent(
                        ChatStreamEvent.TYPE_FALLBACK,
                        text = "Focus on central control [s1].",
                        composerId = "template-chat-v1",
                    ),
                )
            },
        )
        val vm = ChatViewModel(chat, scope = testScope())

        vm.send(gameState, "Is this good?")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.streaming)
        assertEquals(1, state.messages.count { it.role == "assistant" })
        // The fallback text wins over the unvalidated partial.
        val assistant = state.messages.last { it.role == "assistant" }
        assertEquals("Focus on central control [s1].", assistant.text)
        assertEquals(true, assistant.isFallback)
    }

    @Test
    fun `stream error surfaces retry and no assistant message`() = runTest {
        val chat = fakeChat(
            flow<ChatStreamEvent> { throw RuntimeException("network down") },
        )
        val vm = ChatViewModel(chat, scope = testScope())

        vm.send(gameState, "Hello?")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.streaming)
        assertEquals(true, state.error)
        assertEquals(true, state.canRetry)
        // Only the user turn is recorded; no assistant reply.
        assertEquals(1, state.messages.size)
        assertEquals("user", state.messages.single().role)
    }

    @Test
    fun `history is bounded to the last N turns`() = runTest {
        var capturedHistory: List<com.example.coachapi.ChatTurn>? = null
        val chat = object : PositionChat {
            override fun stream(request: PositionChatRequest): Flow<ChatStreamEvent> {
                capturedHistory = request.history
                return flow {
                    emit(ChatStreamEvent(ChatStreamEvent.TYPE_TOKEN, text = "ok [s1]."))
                    emit(ChatStreamEvent(ChatStreamEvent.TYPE_DONE, composerId = "llm-chat-v1"))
                }
            }
            override fun close() = Unit
        }
        val vm = ChatViewModel(chat, scope = testScope())

        // Send several turns to exceed the window.
        repeat(5) { i ->
            vm.send(gameState, "turn $i")
            advanceUntilIdle()
        }

        // The last request's history never exceeds MAX_HISTORY_TURNS (the prior user+assistant pairs).
        assertTrue(capturedHistory!!.size <= ChatViewModel.MAX_HISTORY_TURNS)
    }

    private fun fakeChat(events: Flow<ChatStreamEvent>): PositionChat = object : PositionChat {
        override fun stream(request: PositionChatRequest): Flow<ChatStreamEvent> = events
        override fun close() = Unit
    }
}
