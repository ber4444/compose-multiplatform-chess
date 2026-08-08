package com.example.myapplication.rules

import com.example.ondeviceai.AiContextSnapshot
import com.example.ondeviceai.AiUserSetting
import com.example.ondeviceai.DefaultRulesQaOrchestrator
import com.example.ondeviceai.RulesQaAnswerer
import com.example.ondeviceai.RulesQaModelOutput
import com.example.ondeviceai.VendorRoute
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RulesQaStateHolderTest {
    @Test
    fun `ask exposes a cited offline answer`() = runTest {
        val holder = RulesQaStateHolder(
            DefaultRulesQaOrchestrator(
                answerer = RulesQaAnswerer { _, _ ->
                    RulesQaModelOutput("En passant is immediate [en-passant].", listOf("en-passant"))
                },
                contextProvider = {
                    AiContextSnapshot(
                        availableLocalVendors = listOf(VendorRoute.CactusLocal()),
                        userSetting = AiUserSetting.OFFLINE_ONLY,
                    )
                },
            ),
        )

        holder.ask("When can I capture en passant?")

        val ready = assertIs<RulesQaUiState.Ready>(holder.state.value)
        assertEquals(listOf("en-passant"), ready.passageIds)
        assertEquals(false, ready.isFallback)
    }

    @Test
    fun `corpus ids are stripped from the displayed answer but still listed as sources`() = runTest {
        // Observed on device: the answer rendered as "[draw-dead-position] The game is drawn…".
        // The ids have to survive as far as RulesQaResponseValidator, which checks for them, and be
        // removed on the way to the screen — this surface was the only display path not doing that.
        val holder = RulesQaStateHolder(
            DefaultRulesQaOrchestrator(
                answerer = RulesQaAnswerer { _, _ ->
                    RulesQaModelOutput(
                        "With only kings left neither side can mate [draw-dead-position].",
                        listOf("draw-dead-position"),
                    )
                },
                contextProvider = {
                    AiContextSnapshot(
                        availableLocalVendors = listOf(VendorRoute.CactusLocal()),
                        userSetting = AiUserSetting.OFFLINE_ONLY,
                    )
                },
            ),
        )

        holder.ask("Game is a draw when only kings remain?")

        val ready = assertIs<RulesQaUiState.Ready>(holder.state.value)
        assertEquals("With only kings left neither side can mate.", ready.text)
        assertEquals(listOf("draw-dead-position"), ready.passageIds)
        // What the "Sources:" line renders: a title, not the slug we just stripped from the answer.
        assertEquals(
            listOf("Draw by dead position and insufficient material"),
            ready.sources.map { it.title },
        )
    }

    @Test
    fun `missing platform answerer is visible as unavailable`() {
        val holder = RulesQaStateHolder(null)

        assertIs<RulesQaUiState.Unavailable>(holder.state.value)
    }

    @Test
    fun `cancelled screen request resets loading state`() = runTest {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<RulesQaModelOutput>()
        val holder = RulesQaStateHolder(
            DefaultRulesQaOrchestrator(
                answerer = RulesQaAnswerer { _, _ -> started.complete(Unit); never.await() },
                contextProvider = {
                    AiContextSnapshot(
                        availableLocalVendors = listOf(VendorRoute.CactusLocal()),
                        userSetting = AiUserSetting.OFFLINE_ONLY,
                    )
                },
            ),
        )

        val request = launch { holder.ask("What is stalemate?") }
        started.await()
        request.cancelAndJoin()

        assertIs<RulesQaUiState.Idle>(holder.state.value)
    }
}
