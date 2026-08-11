@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.myapplication.board3d

import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Pins the driver's dirty signal, which answers "was a frame published recently?".
 *
 * The regression these tests exist for: the signal used to be raised only around the animation
 * loop, so every path that publishes a scene *without* animating — mount, new game, an idle-board
 * coach highlight, post-init refresh — reported "nothing to draw" while handing the backend a new
 * frame. A backend gating its render loop on that (SceneView's `isRendering`) would show a stale
 * frame, or at mount never draw the board at all.
 */
class Board3DAnimationDriverTest {

    // ── the non-looping publish paths: the actual bug ─────────────────────────

    @Test
    fun `initial position with no transition raises the dirty signal`() = runTest {
        val h = harness()

        h.driver.setPosition(scene(BoardSquare(6, 4)), transition = null)

        assertEquals(1, h.frames.size, "mount must publish the board")
        assertTrue(h.driver.isDirty, "mount publishes a frame with the loop parked; it must be drawn")
        assertEquals(listOf(true), h.dirtyLog)
    }

    @Test
    fun `reset transition raises the dirty signal`() = runTest {
        val h = harness()
        h.driver.setPosition(scene(BoardSquare(6, 4)), transition = null)
        h.settle()

        h.driver.setPosition(scene(BoardSquare(1, 4)), transition = Board3DTransition.Reset)

        assertEquals(2, h.frames.size)
        assertTrue(h.driver.isDirty, "a new game publishes without animating")
    }

    @Test
    fun `highlight on an idle board raises the dirty signal`() = runTest {
        val h = harness()
        h.driver.setPosition(scene(BoardSquare(6, 4)), transition = null)
        h.settle()
        assertFalse(h.driver.isDirty)

        h.driver.setHighlighted(listOf(BoardSquare(4, 4)))

        assertTrue(h.driver.isDirty, "the coach highlight lands with the loop parked")
        assertEquals(listOf(BoardSquare(4, 4)), h.frames.last().highlightedSquares)
    }

    @Test
    fun `refresh on an idle board raises the dirty signal`() = runTest {
        val h = harness()
        h.driver.setPosition(scene(BoardSquare(6, 4)), transition = null)
        h.settle()
        assertFalse(h.driver.isDirty)

        h.driver.refresh()

        assertTrue(h.driver.isDirty, "async backend init re-emits the resting scene")
    }

    // ── the animated path ─────────────────────────────────────────────────────

    @Test
    fun `an animated move holds the signal up until after the settle frame`() = runTest {
        val h = harness()
        h.driver.setPosition(scene(BoardSquare(6, 4)), transition = null)
        h.settle()
        h.events.clear()
        h.frames.clear()

        h.driver.setPosition(
            scene(BoardSquare(4, 4)),
            Board3DTransition.Move(BoardSquare(6, 4), BoardSquare(4, 4), PieceKind.PAWN, PieceColor.WHITE),
        )

        // Mid-arc: frames are landing and the signal is up.
        advanceTimeBy(PIECE_MOVE_DURATION_MS / 2)
        assertTrue(h.driver.isDirty)
        assertTrue(h.frames.size > 1, "the loop should be ticking")

        advanceUntilIdle()
        assertFalse(h.driver.isDirty, "the window closes once the board is settled")

        // The signal must outlive the *last* frame, never drop before it.
        val lastFrame = h.events.indexOfLast { it == FRAME }
        val lowered = h.events.indexOfLast { it == DIRTY_FALSE }
        assertTrue(lowered > lastFrame, "signal dropped at $lowered, before the settle frame at $lastFrame")
        assertEquals(listOf(FRAME, DIRTY_FALSE), h.events.subList(lastFrame, h.events.size))
    }

    @Test
    fun `the signal stays up for the whole window after the last frame`() = runTest {
        val h = harness()

        h.driver.setPosition(scene(BoardSquare(6, 4)), transition = null)

        advanceTimeBy(DIRTY_WINDOW_MS - 1)
        assertTrue(h.driver.isDirty, "still inside the window")
        advanceTimeBy(2)
        assertFalse(h.driver.isDirty, "window elapsed")
    }

    @Test
    fun `a camera-only change extends the window without publishing a frame`() = runTest {
        val h = harness()
        h.driver.setPosition(scene(BoardSquare(6, 4)), transition = null)
        h.settle()

        h.driver.markDirty()

        assertTrue(h.driver.isDirty)
        assertEquals(1, h.frames.size, "a camera drag publishes no scene")
        h.settle()
        assertFalse(h.driver.isDirty)
    }

    @Test
    fun `cancel clears the signal`() = runTest {
        val h = harness()
        h.driver.setPosition(
            scene(BoardSquare(4, 4)),
            Board3DTransition.Move(BoardSquare(6, 4), BoardSquare(4, 4), PieceKind.PAWN, PieceColor.WHITE),
        )
        advanceTimeBy(FRAME_BUDGET_MS * 2)
        assertTrue(h.driver.isDirty)

        h.driver.cancel()

        assertFalse(h.driver.isDirty)
        assertEquals(DIRTY_FALSE, h.events.last())
    }

    // ── harness ───────────────────────────────────────────────────────────────

    private companion object {
        const val FRAME_BUDGET_MS = 16L

        /** Mirrors the driver's private DIRTY_WINDOW_FRAMES = 3. */
        const val DIRTY_WINDOW_MS = FRAME_BUDGET_MS * 3

        const val FRAME = "frame"
        const val DIRTY_TRUE = "dirty=true"
        const val DIRTY_FALSE = "dirty=false"
    }

    private class Harness(
        val driver: Board3DAnimationDriver,
        val frames: MutableList<Board3DScene>,
        val events: MutableList<String>,
        private val idle: () -> Unit,
    ) {
        val dirtyLog: List<Boolean> get() = events.filter { it != FRAME }.map { it == DIRTY_TRUE }

        /** Run out the animation loop and the dirty window. */
        fun settle() = idle()
    }

    private fun TestScope.harness(): Harness {
        val frames = mutableListOf<Board3DScene>()
        val events = mutableListOf<String>()
        // The driver runs on the TestScope itself, not `backgroundScope`: `advanceUntilIdle` only
        // advances virtual time for foreground work, so a background driver would never reach the
        // end of its own dirty window.
        val driver = Board3DAnimationDriver(
            scope = this,
            frameBudgetMs = FRAME_BUDGET_MS,
            clock = SchedulerTimeSource(testScheduler),
            onDirtyChanged = { events += if (it) DIRTY_TRUE else DIRTY_FALSE },
        ) { scene ->
            frames += scene
            events += FRAME
        }
        return Harness(driver, frames, events) { advanceUntilIdle() }
    }

    private fun scene(pawnAt: BoardSquare) = Board3DScene(
        pieces = listOf(
            Piece3DInstance(PieceKind.PAWN, PieceColor.WHITE, pawnAt, BoardGeometry.squareCenter(pawnAt), 0f),
        ),
        sideToMove = PieceColor.WHITE,
    )
}

/**
 * A [TimeSource] reading the test scheduler's virtual clock, so the driver's dirty window advances
 * in lockstep with `advanceTimeBy` instead of wall time. Without it the window could only be tested
 * with a real sleep.
 */
private class SchedulerTimeSource(private val scheduler: TestCoroutineScheduler) : TimeSource {
    override fun markNow(): TimeMark = Mark(scheduler, scheduler.currentTime)

    private class Mark(
        private val scheduler: TestCoroutineScheduler,
        private val startMs: Long,
    ) : TimeMark {
        override fun elapsedNow(): Duration = (scheduler.currentTime - startMs).milliseconds
    }
}
