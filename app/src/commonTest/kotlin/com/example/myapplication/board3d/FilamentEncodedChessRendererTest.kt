package com.example.myapplication.board3d

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

private class FakeFilamentPeer : FilamentChessPeer {
    val events = mutableListOf<String>()
    var isShutdown = false

    /** Latest [setRenderingActive] value; null until the renderer has said anything. */
    var renderingActive: Boolean? = null
        private set

    override fun setScene(encoded: String) {
        events += "scene:$encoded"
    }

    override fun setCamera(encoded: String) {
        events += "camera:$encoded"
    }

    override fun resize(widthPx: Int, heightPx: Int) {
        events += "resize:${widthPx}x$heightPx"
    }

    override fun setRenderingActive(active: Boolean) {
        renderingActive = active
        events += "rendering:$active"
    }

    override fun attach(surface: Chess3DSurface?) {
        events += "attach:${surface?.widthPx ?: 0}x${surface?.heightPx ?: 0}"
    }

    override fun detach() {
        events += "detach"
    }

    override fun shutdown() {
        isShutdown = true
        events += "shutdown"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FilamentEncodedChessRendererTest {
    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

    @Test
    fun `position before attach is rendered when attached`() = runTest {
        val peer = FakeFilamentPeer()
        val renderer = renderer(peer)
        renderer.updatePosition(startFen)

        // Nothing to *draw* reaches a peer that hasn't been attached. The render-loop signal is not
        // in that set and is expected here: it reports the driver's state, and forwarding it
        // unconditionally is what guarantees the peer also hears the matching "stop" — suppressing
        // that edge while detached is how a display link ends up running forever.
        assertEquals(
            emptyList(),
            peer.events.filter { it.startsWith("scene:") || it.startsWith("camera:") || it.startsWith("resize:") },
        )

        renderer.attach(surface(320, 240))
        advanceUntilIdle()

        // First of the *drawing* events, for the same reason as above: the render-loop signal is
        // already in the log by now.
        val drawEvents = peer.events.filter { !it.startsWith("rendering:") }
        assertEquals("attach:320x240", drawEvents.first())
        assertTrue(drawEvents.any { it.startsWith("camera:") })
        assertTrue(drawEvents.any { it.startsWith("scene:") })
    }

    @Test
    fun `resize updates peer and camera aspect`() = runTest {
        val peer = FakeFilamentPeer()
        val renderer = renderer(peer)
        renderer.attach(surface(100, 100))

        renderer.onUserInteraction(Board3DInput.Resize(400, 200))

        assertTrue(peer.events.contains("resize:400x200"))
        assertTrue(peer.events.last { it.startsWith("camera:") }.endsWith(",2.0"))
    }

    @Test
    fun `zero size attach does not resize peer`() = runTest {
        val peer = FakeFilamentPeer()
        val renderer = renderer(peer)

        renderer.attach(surface(0, 0))

        assertTrue(peer.events.contains("attach:0x0"))
        assertTrue(peer.events.none { it.startsWith("resize:") })
    }

    @Test
    fun `dispose shuts down peer once`() {
        val peer = FakeFilamentPeer()
        val renderer = FilamentEncodedChessRenderer(peer)

        renderer.dispose()
        renderer.dispose()

        assertTrue(peer.isShutdown)
        assertEquals(1, peer.events.count { it == "shutdown" })
    }

    // ── the render-loop signal ────────────────────────────────────────────────
    //
    // This is the seam the iOS backend parks its CADisplayLink on (and the one Android already
    // parks SceneView's frame loop on): an idle 3D board otherwise redraws at the panel refresh
    // rate for as long as it is on screen. Board3DAnimationDriverTest pins the signal itself; what
    // these pin is that it survives the trip through the peer — including on the paths that publish
    // a scene *without* animating, which is where a "is an animation running" signal goes wrong.

    @Test
    // No comma in the name: Kotlin/Native rejects it, and this suite runs on iosSimulatorArm64.
    fun `mount asks the peer to keep drawing - and only after the scene it has to draw`() = runTest {
        val peer = FakeFilamentPeer()

        renderer(peer).attach(surface(320, 240))

        val scene = peer.events.indexOfFirst { it.startsWith("scene:") }
        val wake = peer.events.indexOfFirst { it == "rendering:true" }
        assertTrue(scene >= 0, "mount must publish the board: ${peer.events}")
        assertTrue(wake > scene, "the peer was woken before it had the scene: ${peer.events}")
    }

    @Test
    fun `a settled board parks the render loop`() = runTest {
        val peer = FakeFilamentPeer()
        renderer(peer).attach(surface(320, 240))

        advanceUntilIdle()

        assertEquals(false, peer.renderingActive, "nothing is animating; the board must stop drawing")
    }

    @Test
    fun `a new game wakes the loop without animating`() = runTest {
        val peer = FakeFilamentPeer()
        val renderer = renderer(peer)
        renderer.attach(surface(320, 240))
        advanceUntilIdle()

        renderer.updatePosition(startFen, Board3DTransition.Reset)

        assertEquals(true, peer.renderingActive)
    }

    @Test
    fun `a coach highlight landing on an idle board wakes the loop`() = runTest {
        val peer = FakeFilamentPeer()
        val renderer = renderer(peer)
        renderer.attach(surface(320, 240))
        advanceUntilIdle()
        peer.events.clear()

        renderer.setHighlightedSquares(
            listOf(HighlightedSquare(BoardSquare(4, 4), HighlightTone.GOOD))
        )

        assertEquals(true, peer.renderingActive)
        assertTrue(peer.events.any { it.startsWith("scene:") }, "the highlight must reach the peer")
    }

    @Test
    fun `re-attaching after async backend init wakes the loop`() = runTest {
        val peer = FakeFilamentPeer()
        val renderer = renderer(peer)
        renderer.attach(surface(320, 240))
        advanceUntilIdle()
        renderer.detach()

        renderer.attach(surface(320, 240))

        assertEquals(true, peer.renderingActive)
    }

    @Test
    fun `a camera drag wakes the loop although it publishes no scene`() = runTest {
        val peer = FakeFilamentPeer()
        val renderer = renderer(peer)
        renderer.attach(surface(320, 240))
        advanceUntilIdle()
        peer.events.clear()

        renderer.onUserInteraction(
            Board3DInput.SetCamera(OrbitCameraController.DEFAULT_WHITE_VIEW.copy(aspect = 1.5f))
        )

        assertEquals(true, peer.renderingActive, "a parked loop would sit out the whole drag")
        assertTrue(peer.events.any { it.startsWith("camera:") })
        assertTrue(peer.events.none { it.startsWith("scene:") }, "a camera-only change draws no scene")
    }

    @Test
    fun `an animated move holds the loop open until the board settles`() = runTest {
        val peer = FakeFilamentPeer()
        val renderer = renderer(peer)
        renderer.attach(surface(320, 240))
        advanceUntilIdle()

        renderer.updatePosition(
            afterE4,
            Board3DTransition.Move(BoardSquare(6, 4), BoardSquare(4, 4), PieceKind.PAWN, PieceColor.WHITE),
        )
        // Mid-arc: the hop runs for PIECE_MOVE_DURATION_MS (500 ms), which is `internal` to
        // chess-core and so not nameable from here.
        advanceTimeBy(250)
        assertEquals(true, peer.renderingActive, "mid-arc")

        advanceUntilIdle()
        assertEquals(false, peer.renderingActive)

        // The wake must outlive the last frame — parking one publish early strands the settle frame,
        // which is the one that shows the piece on its destination square.
        val lastScene = peer.events.indexOfLast { it.startsWith("scene:") }
        val park = peer.events.indexOfLast { it == "rendering:false" }
        assertTrue(park > lastScene, "parked at $park, before the settle frame at $lastScene")
    }

    @Test
    fun `dispose parks the loop before the peer is torn down`() {
        val peer = FakeFilamentPeer()
        val renderer = FilamentEncodedChessRenderer(peer)

        renderer.dispose()

        assertFalse(peer.events.contains("rendering:true"))
        assertTrue(peer.isShutdown)
    }

    // ── harness ───────────────────────────────────────────────────────────────

    private fun TestScope.renderer(peer: FakeFilamentPeer) = FilamentEncodedChessRenderer(
        peer,
        TestScope(StandardTestDispatcher(testScheduler)),
        // The dirty window closes on a real duration; without a scheduler-backed clock the settle
        // coroutine's `delay` completes instantly on virtual time while the window never elapses,
        // and `advanceUntilIdle` spins for the window's worth of wall time instead.
        clock = SchedulerTimeSource(testScheduler),
    )

    private fun surface(width: Int, height: Int) = object : Chess3DSurface {
        override val widthPx = width
        override val heightPx = height
    }
}

/**
 * A [TimeSource] reading the test scheduler's virtual clock, so the driver's dirty window advances
 * with `advanceTimeBy` instead of wall time. Mirrors the identically-named class in
 * `Board3DAnimationDriverTest`; it can't be shared, since that one lives in chess-core's test source
 * set.
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
