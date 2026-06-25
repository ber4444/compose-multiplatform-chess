package com.example.myapplication.board3d

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

interface FilamentChessPeer {
    fun setScene(encoded: String)
    fun setCamera(encoded: String)
    fun resize(widthPx: Int, heightPx: Int)
    fun attach(surface: Chess3DSurface?)
    fun detach()
    fun shutdown()
}

class FilamentEncodedChessRenderer(
    private val peer: FilamentChessPeer,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
) : Chess3DBoardRenderer {
    private var pendingFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private var camera = OrbitCameraController.DEFAULT_WHITE_VIEW
    private var selectedSquare: BoardSquare? = null
    private var isReady = false
    private var isDisposed = false

    private val driver = Board3DAnimationDriver(scope) { scene ->
        if (isReady && !isDisposed) peer.setScene(scene.encode())
    }

    override fun attach(surface: Chess3DSurface) {
        if (isDisposed) return
        isReady = true
        peer.attach(surface)
        peer.resize(surface.widthPx, surface.heightPx)
        camera = camera.copy(aspect = surface.widthPx.toFloat() / surface.heightPx.coerceAtLeast(1).toFloat())
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
            is Board3DInput.SetCamera -> {
                camera = event.camera
                applyCamera()
            }
            is Board3DInput.Resize -> {
                if (event.widthPx > 0 && event.heightPx > 0) {
                    camera = camera.copy(aspect = event.widthPx.toFloat() / event.heightPx.toFloat())
                    if (isReady) peer.resize(event.widthPx, event.heightPx)
                    applyCamera()
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
