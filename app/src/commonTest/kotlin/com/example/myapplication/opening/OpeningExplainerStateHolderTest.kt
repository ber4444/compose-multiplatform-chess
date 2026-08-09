package com.example.myapplication.opening

import com.example.coachapi.OpeningExplainResponse
import com.example.coachapi.Passage
import com.example.myapplication.GameUiState
import com.example.myapplication.MoveRecord
import com.example.myapplication.WinState
import com.example.ondeviceai.AiRoute
import com.example.ondeviceai.OpeningExplainer
import com.example.ondeviceai.OpeningExplainerResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpeningExplainerStateHolderTest {
    @Test
    fun `finished game maps explanation and source titles to ready state`() = runTest {
        val response = OpeningExplainResponse(
            "A central opening.",
            listOf(Passage("c20", "King's Pawn Game", "A central opening.")),
            "template-v1",
        )
        val holder = OpeningExplainerStateHolder(
            OpeningExplainer { OpeningExplainerResult.Success(response, AiRoute.Cloud) },
        )

        holder.explain(GameUiState(winState = WinState.DRAW))

        val ready = assertIs<OpeningExplainerUiState.Ready>(holder.state.value)
        assertEquals("A central opening.", ready.text)
        assertEquals(listOf("King's Pawn Game"), ready.sourceTitles)
        assertEquals(AiRoute.Cloud, ready.route)
    }

    @Test
    fun `finished game sends only the opening move prefix`() = runTest {
        var capturedMoves = emptyList<String>()
        val history = (1..40).map { ply ->
            MoveRecord("a2a3", "move-$ply", "fen-$ply")
        }
        val holder = OpeningExplainerStateHolder(
            OpeningExplainer { request ->
                capturedMoves = request.movesSan
                OpeningExplainerResult.Success(
                    OpeningExplainResponse("Opening", emptyList(), "template-v1"),
                    AiRoute.Cloud,
                )
            },
        )

        holder.explain(GameUiState(winState = WinState.DRAW, moveHistory = history))

        assertEquals((1..20).map { "move-$it" }, capturedMoves)
    }
}
