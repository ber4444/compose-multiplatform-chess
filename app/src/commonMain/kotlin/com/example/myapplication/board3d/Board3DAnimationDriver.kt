package com.example.myapplication.board3d

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * Backend-agnostic driver for the 3D board's piece animations.
 *
 * Holds the resting position plus any in-flight move/selection state and, on a frame-paced coroutine
 * loop, emits the interpolated [Board3DScene] for the current instant through [render]. Each renderer
 * supplies a [render] callback that draws a scene the way its backend wants (desktop: build + upload
 * Vulkan geometry; Android: publish Compose snapshot state; three.js: push the encoded scene to JS)
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
 * Threading: every public method and [render] call runs on [scope]'s thread. Renderers whose render
 * thread differs from where [updatePosition]/[setSelected] are called (e.g. desktop) must marshal
 * those calls onto [scope] themselves, exactly as they already marshal native work.
 */
class Board3DAnimationDriver(
    private val scope: CoroutineScope,
    private val frameBudgetMs: Long = 16L,
    private val render: (Board3DScene) -> Unit,
) {
    private val clock = TimeSource.Monotonic
    private var resting: Board3DScene? = null
    private var move: Board3DTransition? = null
    private var moveStart = clock.markNow()
    private var selected: BoardSquare? = null
    private var selectStart = clock.markNow()
    private var job: Job? = null

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

    /** Re-emit the current resting position (e.g. once the backend finishes async init). */
    fun refresh() {
        if (move == null && selected == null) renderResting() else ensureLoop()
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun renderResting() {
        resting?.let(render)
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

                // Bounce the selected piece only while it's resting (not mid-move).
                val sel = selected
                val out = if (move == null && sel != null) {
                    scene.withSelectionLift(sel, selectionBounceOffset(selectStart.elapsedNow().inWholeMilliseconds))
                } else {
                    scene
                }
                render(out)

                // Nothing left to animate: settle on the resting position and stop ticking.
                if (move == null && selected == null) {
                    render(rest)
                    break
                }

                val spent = frameStart.elapsedNow().inWholeMilliseconds
                delay((frameBudgetMs - spent).coerceAtLeast(0L))
            }
        }
    }
}
