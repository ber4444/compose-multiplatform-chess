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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real milliseconds, since the coach runs off the test scheduler. Well under `runTest`'s own bound. */
private const val TERMINAL_STATE_TIMEOUT_MS = 5_000L

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

    /** A manager with no orchestrator at all — Android, and any build with no on-device model. */
    private fun managerWithoutOrchestrator(): MoveCoachManager {
        vm = GameViewModel()
        vm.aiCoachEnabled = true
        return MoveCoachManager(vm).apply { proUnlocked = true }
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

    /**
     * Waits for the *terminal* state. [MoveCoachUiState.Loading] is also `Toned`, so stopping at the
     * first toned state raced the orchestrator and read the placeholder.
     *
     * The wait deliberately runs on [Dispatchers.Default] rather than on `runTest`'s scheduler.
     * [MoveCoachManager] owns a real `Dispatchers.Default` scope, so its progress is invisible to
     * virtual time: the poll loop this replaced spent its whole nominal two-second budget in a few
     * hundred microseconds of wall clock, and whether the coach had finished by then was pure luck.
     * It won on a warm developer machine and lost on a loaded CI runner. Suspending on the flow
     * instead of polling also means the common case costs one dispatch, not 200.
     */
    private suspend fun waitForToned(manager: MoveCoachManager) {
        val terminal = withContext(Dispatchers.Default) {
            withTimeoutOrNull(TERMINAL_STATE_TIMEOUT_MS) {
                manager.coachUiState.first {
                    it is MoveCoachUiState.Toned && it !is MoveCoachUiState.Loading
                }
            }
        }
        assertTrue(
            terminal != null,
            "coach never produced a terminal toned state: ${manager.coachUiState.value}",
        )
    }

    @Test
    fun `a fallback does not repeat the verdict the board is already showing`() = runTest {
        // The orchestrator concatenates "<headline> <explanation>". Left whole, the most common
        // state in the app was the one state that still said "Best move — Qc2" next to a green Qc2.
        val manager = manager(
            MoveCoachResult.FellBack(
                text = "Blunder — forks It attacks two pieces at once.",
                reason = AiRoutePolicyDecider.FallbackReason.NoLocalModel,
            ),
        )
        coach(blunder())
        waitForToned(manager)

        val state = manager.coachUiState.value as MoveCoachUiState.Fallback
        assertEquals("It attacks two pieces at once.", state.text)
        assertEquals("Blunder — forks", state.headline)
        manager.close()
        vm.close()
    }

    // --- no orchestrator is a product state, not a dead panel ------------------------------------

    @Test
    fun `a build with no orchestrator still shows the deterministic line`() = runTest {
        // Two separate gates used to suppress the panel entirely when no model was attached: the
        // `orchestrator != null` condition on the onMoveCoached callback, and the early return at
        // the top of launchCoach. Either one alone leaves a blank panel, which is what Android would
        // have shipped once it stopped attaching an orchestrator. The deterministic coach is a
        // complete answer and must not need a model in order to be seen.
        val manager = managerWithoutOrchestrator()
        coach(blunder())
        waitForToned(manager)

        val state = manager.coachUiState.value
        val ready = assertIs<MoveCoachUiState.Ready>(state)
        assertTrue(ready.explanation.explanation.isNotBlank(), "no deterministic text")
        assertEquals(HighlightTone.BAD, state.highlightTone, "a blunder must still be red")
        manager.close()
        vm.close()
    }

    @Test
    fun `no orchestrator is reported as no local model rather than the free tier`() = runTest {
        // The distinction reaches the log line and FallbackPresentation. Free tier means a model may
        // exist and the user simply hasn't unlocked it; no orchestrator means this platform has none
        // to unlock, and an upsell for it would be selling something that does not exist.
        val manager = managerWithoutOrchestrator()
        coach(blunder())
        waitForToned(manager)

        val ready = assertIs<MoveCoachUiState.Ready>(manager.coachUiState.value)
        val route = assertIs<com.example.ondeviceai.AiRoute.Fallback>(ready.explanation.route)
        assertEquals(AiRoutePolicyDecider.FallbackReason.NoLocalModel, route.reason)
        manager.close()
        vm.close()
    }
}
