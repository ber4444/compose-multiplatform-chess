package com.example.myapplication.board3d

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/** How long the dirty signal outlives the last published frame, in [frameBudgetMs] units. */
private const val DIRTY_WINDOW_FRAMES = 3

/**
 * Backend-agnostic driver for the 3D board's piece animations.
 *
 * Holds the resting position plus any in-flight move/selection state and, on a frame-paced coroutine
 * loop, emits the interpolated [Board3DScene] for the current instant through [render]. Each renderer
 * supplies a [render] callback that draws a scene the way its backend wants (desktop/iOS/web:
 * push an encoded Filament scene; Android: publish Compose snapshot state for SceneView)
 * and a [scope] running on the thread its renderer expects to be driven from.
 *
 * This centralises what every backend would otherwise reimplement: the vkChess move arc hop
 * ([Board3DMoveAnimator] / [catmullRomArc]), the selection bounce ([selectionBounceOffset] +
 * [withSelectionLift]), frame pacing, and stopping the loop once nothing is animating.
 *
 * [frameBudgetMs] is the target per-frame interval (~60fps). The loop measures the time spent in
 * [render] (which on desktop blocks on the GPU) and sleeps only the remainder, so a slow render
 * doesn't stack on top of a flat delay and halve the rate.
 *
 * ## The dirty signal
 *
 * [isDirty] / [onDirtyChanged] answer **"was a frame published recently?"**, not "is the animation
 * loop running?". Every scene this driver emits goes through one private `publish`, which re-arms
 * the signal; it then stays raised for [DIRTY_WINDOW_FRAMES] frame budgets past the *last* published
 * frame, so the settle frame of any change is guaranteed to reach the screen before a backend parks
 * its render loop.
 *
 * The distinction is load-bearing for backends that gate rendering on this (Android/SceneView's
 * `isRendering`). A "loop is running" signal is **false** on every path that publishes a scene
 * without animating: the initial [setPosition] at mount, a [Board3DTransition.Reset] (new game), a
 * coach highlight landing on an idle board ([setHighlighted]), and [refresh] after async backend
 * init. Gating on it strands a stale frame — at mount, it never draws the board at all.
 *
 * [markDirty] extends the same window to changes this driver does not render itself (a camera drag),
 * so a renderer needs exactly one signal rather than one per reason.
 *
 * Threading: every public method and [render] call runs on [scope]'s thread. Renderers whose render
 * thread differs from where [updatePosition]/[setSelected] are called (e.g. desktop) must marshal
 * those calls onto [scope] themselves, exactly as they already marshal native work. [isDirty] is a
 * plain read — this module is Compose-free, so a renderer that needs it *reactively* mirrors
 * [onDirtyChanged] into whatever observable state its UI toolkit uses.
 *
 * [clock] is injectable so the dirty window is testable on virtual time; production uses
 * [TimeSource.Monotonic].
 */
class Board3DAnimationDriver(
    private val scope: CoroutineScope,
    private val frameBudgetMs: Long = 16L,
    private val clock: TimeSource = TimeSource.Monotonic,
    private val onDirtyChanged: (Boolean) -> Unit = {},
    private val render: (Board3DScene) -> Unit,
) {
    private var resting: Board3DScene? = null
    private var move: Board3DTransition? = null
    private var moveStart = clock.markNow()
    private var selected: BoardSquare? = null
    private var selectStart = clock.markNow()
    private var highlighted: List<BoardSquare> = emptyList()
    private var job: Job? = null

    private val dirtyWindowMs: Long get() = frameBudgetMs * DIRTY_WINDOW_FRAMES
    private var dirty = false
    private var lastPublish = clock.markNow()
    private var settleJob: Job? = null

    /** True while a frame was published within the last [DIRTY_WINDOW_FRAMES] frame budgets. */
    val isDirty: Boolean get() = dirty

    /**
     * New board position. A non-null [transition] that isn't [Board3DTransition.Reset] plays the move
     * hop from the previous squares; otherwise the position is shown immediately (no animation).
     */
    fun setPosition(scene: Board3DScene?, transition: Board3DTransition?) {
        resting = scene
        move = transition?.takeIf { it !is Board3DTransition.Reset && scene != null }
        if (move != null) {
            moveStart = clock.markNow()
            ensureLoop()
        } else if (selected != null) {
            ensureLoop()
        } else {
            renderResting()
        }
    }

    /** Select [square] (bounce it) or clear the selection. */
    fun setSelected(square: BoardSquare?) {
        if (square == selected) return
        selected = square
        if (square != null) {
            selectStart = clock.markNow()
            ensureLoop()
        } else if (move == null) {
            renderResting()
        }
    }

    /** Set the highlighted squares (for move coach). */
    fun setHighlighted(squares: List<BoardSquare>) {
        if (squares == highlighted) return
        highlighted = squares
        if (move == null && selected == null) {
            renderResting()
        } else {
            ensureLoop()
        }
    }

    /** Re-emit the current resting position (e.g. once the backend finishes async init). */
    fun refresh() {
        if (move == null && selected == null) renderResting() else ensureLoop()
    }

    /**
     * Raise the dirty signal for a change this driver didn't render — a camera drag moves the view
     * without touching the scene, so no frame is published, yet the backend still has to redraw.
     * Cheap enough to call per input event: it stamps a timestamp and keeps at most one settle
     * coroutine alive, rather than allocating (and cancelling) a debounce job per event.
     */
    fun markDirty() {
        lastPublish = clock.markNow()
        setDirty(true)
        ensureSettleWatch()
    }

    fun cancel() {
        job?.cancel()
        job = null
        settleJob?.cancel()
        settleJob = null
        setDirty(false)
    }

    private fun renderResting() {
        // Must carry selected/highlighted the same way the animation loop below does — this is the
        // path taken whenever a highlight or selection changes while the board is otherwise idle
        // (e.g. the move coach highlighting squares right after a move settles), so rendering the
        // bare `resting` scene here silently drops both.
        resting?.let { publish(it.copy(selectedSquare = selected, highlightedSquares = highlighted)) }
    }

    /**
     * The single funnel every frame goes through — the loop's and [renderResting]'s alike. Adding a
     * bare `render(...)` call elsewhere would emit a scene the dirty signal never sees, which is
     * exactly the class of bug this indirection exists to prevent.
     */
    private fun publish(scene: Board3DScene) {
        render(scene)
        markDirty()
    }

    private fun setDirty(value: Boolean) {
        if (dirty == value) return
        dirty = value
        onDirtyChanged(value)
    }

    /**
     * One long-lived coroutine that lowers the signal once the window since the last publish has
     * elapsed. It re-reads [lastPublish] after waking, so a frame published mid-wait simply extends
     * the window instead of needing the job cancelled and relaunched.
     */
    private fun ensureSettleWatch() {
        if (settleJob?.isActive == true) return
        settleJob = scope.launch {
            while (isActive) {
                val remaining = dirtyWindowMs - lastPublish.elapsedNow().inWholeMilliseconds
                if (remaining <= 0L) break
                delay(remaining)
            }
            setDirty(false)
        }
    }

    private fun ensureLoop() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val rest = resting ?: break
                val frameStart = clock.markNow()

                var scene = rest
                val mv = move
                if (mv != null) {
                    val progress = (moveStart.elapsedNow().inWholeMilliseconds.toFloat() / PIECE_MOVE_DURATION_MS)
                        .coerceIn(0f, 1f)
                    scene = Board3DMoveAnimator.interpolate(rest, mv, progress)
                    if (progress >= 1f) move = null
                }

                // apply selection and highlights
                scene = scene.copy(
                    selectedSquare = selected,
                    highlightedSquares = highlighted
                )
                // Bounce the selected piece only while it's resting (not mid-move).
                val sel = selected
                val out = if (move == null && sel != null) {
                    scene.withSelectionLift(sel, selectionBounceOffset(selectStart.elapsedNow().inWholeMilliseconds))
                } else {
                    scene
                }
                publish(out)

                // Nothing left to animate: settle on the resting position and stop ticking. Still
                // carries selected/highlighted (selected is null here by the branch condition, but
                // highlighted may not be) — settling must not re-render the bare scene and drop them.
                if (move == null && selected == null) {
                    publish(rest.copy(selectedSquare = selected, highlightedSquares = highlighted))
                    break
                }

                val spent = frameStart.elapsedNow().inWholeMilliseconds
                delay((frameBudgetMs - spent).coerceAtLeast(0L))
            }
        }
    }
}
