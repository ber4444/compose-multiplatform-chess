package com.example.myapplication.board3d

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.TimeSource

interface FilamentChessPeer {
    fun setScene(encoded: String)
    fun setCamera(encoded: String)
    fun resize(widthPx: Int, heightPx: Int)
    fun attach(surface: Chess3DSurface?)
    fun detach()
    fun shutdown()

    /**
     * Whether the backend still has anything to draw. Forwards [Board3DAnimationDriver.isDirty], so
     * it answers **"was a frame published recently"**, not "is an animation running" — the driver
     * publishes without animating on mount, on a new game, on a coach highlight landing on an idle
     * board, and after async init, and a backend gating on the narrower signal strands all four
     * undrawn (at mount, it never draws the board at all). [Board3DAnimationDriverTest] pins those
     * paths.
     *
     * A backend with a free-running render loop must park it while this reads false: an untouched
     * 3D board otherwise redraws at the panel refresh rate for as long as it is on screen. Measured
     * on the Android backend before its `isRendering` gate: 120 fps on a Galaxy Z Fold3, 60 fps and
     * ~1.76 cores on a Pixel 7a, GPU rail alone at 10.36 J per 10 s idle window.
     *
     * Defaulted to a no-op because the desktop peer has no loop to park — it renders once per push,
     * from [setScene]/[setCamera]. The iOS peer parks a `CADisplayLink` on this and the web peer
     * stops scheduling `requestAnimationFrame`; both add the same backstop on their own side, since
     * a peer whose renderer is built asynchronously can be handed a scene *after* the driver's dirty
     * window has already closed, and parking on this signal alone would then never draw the board.
     */
    fun setRenderingActive(active: Boolean) {}
}

class FilamentEncodedChessRenderer(
    private val peer: FilamentChessPeer,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    // Injectable for the same reason the driver's own is: the dirty window closes on a real
    // duration, so a test on virtual time cannot observe the board settle without one.
    clock: TimeSource = TimeSource.Monotonic,
) : Chess3DBoardRenderer {
    private var pendingFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private var camera = OrbitCameraController.DEFAULT_WHITE_VIEW
    private var selectedSquare: BoardSquare? = null
    private var isReady = false
    private var isDisposed = false

    // `publish` calls render(scene) and *then* markDirty(), so the peer always has the new state
    // before it is told to keep drawing — a backend that wakes its loop on the signal never wakes
    // to a stale scene.
    private val driver = Board3DAnimationDriver(
        scope,
        clock = clock,
        onDirtyChanged = peer::setRenderingActive,
    ) { scene ->
        if (isReady && !isDisposed) peer.setScene(scene.encode())
    }

    override fun attach(surface: Chess3DSurface) {
        if (isDisposed) return
        isReady = true
        peer.attach(surface)
        if (surface.widthPx > 0 && surface.heightPx > 0) {
            peer.resize(surface.widthPx, surface.heightPx)
            camera = camera.copy(aspect = surface.widthPx.toFloat() / surface.heightPx.toFloat())
        }
        applyCamera()
        driver.setPosition(runCatching { Board3DSceneMapper.fromFen(pendingFen) }.getOrNull(), null)
        driver.setSelected(selectedSquare)
        driver.refresh()
    }

    override fun detach() {
        if (!isReady || isDisposed) return
        isReady = false
        peer.detach()
    }

    override fun updatePosition(fen: String) = updatePosition(fen, null)

    override fun updatePosition(fen: String, transition: Board3DTransition?) {
        if (isDisposed) return
        pendingFen = fen
        driver.setPosition(runCatching { Board3DSceneMapper.fromFen(fen) }.getOrNull(), transition)
    }

    override fun onUserInteraction(event: Board3DInput) {
        if (isDisposed) return
        when (event) {
            // Camera-only changes move the view without touching the scene, so no frame is
            // published and nothing else raises the signal — a backend parked on it would sit out
            // the whole drag. Mark the driver dirty directly, exactly as
            // AndroidSceneViewChessRenderer does.
            is Board3DInput.SetCamera -> {
                camera = event.camera
                applyCamera()
                driver.markDirty()
            }
            is Board3DInput.Resize -> {
                if (event.widthPx > 0 && event.heightPx > 0) {
                    camera = camera.copy(aspect = event.widthPx.toFloat() / event.heightPx.toFloat())
                    if (isReady) peer.resize(event.widthPx, event.heightPx)
                    applyCamera()
                    driver.markDirty()
                }
            }
            else -> Unit
        }
    }

    override fun setSelectedSquare(square: BoardSquare?) {
        if (isDisposed) return
        selectedSquare = square
        driver.setSelected(square)
    }

    override fun setHighlightedSquares(squares: List<HighlightedSquare>) {
        if (isDisposed) return
        driver.setHighlighted(squares)
    }

    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        isReady = false
        driver.cancel()
        scope.cancel()
        peer.shutdown()
    }

    private fun applyCamera() {
        if (!isReady || isDisposed) return
        val cam = camera
        peer.setCamera(
            "${cam.position.x},${cam.position.y},${cam.position.z}," +
                "${cam.target.x},${cam.target.y},${cam.target.z}," +
                "${cam.up.x},${cam.up.y},${cam.up.z}," +
                "${cam.fovYDegrees},${cam.aspect}"
        )
    }
}
