package com.example.myapplication.rules

import com.example.ondeviceai.AiContextSnapshot
import com.example.ondeviceai.AiRoute
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
        assertEquals(AiRoute.OnDevice, ready.route)
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
