package com.example.myapplication.movecoach

import com.example.myapplication.GameViewModel
import com.example.myapplication.MoveAssessment
import com.example.myapplication.MoveClass
import com.example.myapplication.MoveRecord
import com.example.myapplication.board3d.HighlightTone
import com.example.ondeviceai.AiCoachOrchestrator
import com.example.ondeviceai.MoveCoachEvent
import com.example.ondeviceai.MoveCoachRequest
import com.example.ondeviceai.MoveCoachResult
import com.example.ondeviceai.AiRoutePolicyDecider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gap between "the renderer paints tones" (`DesktopRendererSmokeTest`) and "a blunder is red on
 * a phone".
 *
 * Both halves were individually right the first time this shipped and the board still came up blue,
 * because nothing asserted the join: that the `MoveClass` on the ply reaches
 * [MoveCoachUiState.Toned.tone] on *every* state the panel can land in. The fallback path is the one
 * that matters most in practice — it is what a device without a warm model shows all game.
 */
class MoveCoachToneTest {

    private fun blunder() = MoveRecord(
        uci = "g1f3",
        san = "Nf3",
        fenAfter = "",
        assessment = MoveAssessment(
            cpBefore = 0,
            cpPlayed = -400,
            cpBest = 0,
            cpLoss = 400,
            moveClass = MoveClass.BLUNDER,
            motifs = listOf("fork"),
        ),
    )

    private fun orchestrator(result: MoveCoachResult) = object : AiCoachOrchestrator {
        override suspend fun explainMove(request: MoveCoachRequest): MoveCoachResult = result
        override fun explainMoveStreaming(request: MoveCoachRequest): Flow<MoveCoachEvent> =
            flowOf(MoveCoachEvent.Complete(result))
    }

    private lateinit var vm: GameViewModel

    private fun manager(result: MoveCoachResult, proUnlocked: Boolean = true): MoveCoachManager {
        vm = GameViewModel()
        vm.aiCoachEnabled = true
        return MoveCoachManager(vm).apply {
            this.proUnlocked = proUnlocked
            attachCoachOrchestrator(orchestrator(result))
        }
    }

    /** triggerCoach is private; it fires through the VM callback the manager registers in `init`. */
    private fun coach(record: MoveRecord) {
        vm.onMoveCoached?.invoke("", record)
    }

    @Test
    fun `a blundered move is red on the fallback path`() = runTest {
        val manager = manager(
            MoveCoachResult.FellBack(
                text = "Blunder — forks It attacks two pieces at once.",
                reason = AiRoutePolicyDecider.FallbackReason.NoLocalModel,
            ),
        )
        coach(blunder())
        waitForToned(manager)

        assertEquals(HighlightTone.BAD, manager.coachUiState.value.highlightTone)
        manager.close()
        vm.close()
    }

    @Test
    fun `a blundered move is red on the free tier`() = runTest {
        val manager = manager(
            MoveCoachResult.FellBack("x", AiRoutePolicyDecider.FallbackReason.NoLocalModel),
            proUnlocked = false,
        )
        coach(blunder())
        waitForToned(manager)

        assertEquals(HighlightTone.BAD, manager.coachUiState.value.highlightTone)
        manager.close()
        vm.close()
    }

    @Test
    fun `the move's own squares reach the state rather than whatever the prose named`() =
        runTest {
            val manager = manager(
                MoveCoachResult.FellBack("no square named here", AiRoutePolicyDecider.FallbackReason.NoLocalModel),
            )
            coach(blunder())
            waitForToned(manager)

            val state = manager.coachUiState.value as MoveCoachUiState.Toned
            assertEquals(listOf("g1", "f3"), state.squares)
            manager.close()
        }

    private suspend fun waitForToned(manager: MoveCoachManager) {
        repeat(200) {
            if (manager.coachUiState.value is MoveCoachUiState.Toned) return
            kotlinx.coroutines.delay(10)
        }
        assertTrue(false, "coach never produced a toned state: ${manager.coachUiState.value}")
    }
}
