package com.example.myapplication.board3d

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

/**
 * Compose-observable state holder implementing [Chess3DBoardRenderer] for the SceneView backend.
 *
 * SceneView owns the Filament Engine / render lifecycle through its composable, so
 * [attach], [detach], and [dispose] are all no-ops here.  All mutable state is exposed as
 * [mutableStateOf] snapshot state so that [AndroidBoard3DSurface] recomposes automatically
 * whenever the position, camera, or selection changes.
 *
 * Animation is delegated to the shared [Board3DAnimationDriver]: each frame it publishes the
 * interpolated [boardScene] (move arc hop + selection bounce) into snapshot state, recomposing the
 * reactive piece nodes. Selection is shown by bouncing the piece, not a coloured disc.
 */
class AndroidSceneViewChessRenderer(
    val glbBytes: ByteArray,
) : Chess3DBoardRenderer {

    private var _boardScene by mutableStateOf<Board3DScene?>(null)
    val boardScene: Board3DScene? get() = _boardScene

    private var _cameraParams by mutableStateOf(OrbitCameraController.DEFAULT_WHITE_VIEW)
    val cameraParams: CameraParams get() = _cameraParams

    /**
     * Whether the board has anything new to draw — destined for SceneView's `isRendering` once
     * sceneview/sceneview#3109 lands; nothing consumes it yet.
     *
     * Mirrors [Board3DAnimationDriver.isDirty], which tracks *frames published recently*, not
     * *loop running*: the driver publishes scenes without starting its loop on mount, on a new
     * game, and when a coach highlight lands on an idle board, and the signal has to cover those
     * too or SceneView parks with a stale frame (at mount, with no frame at all). The driver holds
     * it up for a few frame budgets past the last publish so the settle frame is always drawn, and
     * a camera drag extends the same window via [Board3DAnimationDriver.markDirty] — hence one
     * flag here rather than an animation flag OR'd with an interaction flag.
     */
    private var _needsRender by mutableStateOf(false)
    val needsRender: Boolean get() = _needsRender

    private val animScope = CoroutineScope(Dispatchers.Main)
    private val driver = Board3DAnimationDriver(
        animScope,
        onDirtyChanged = { _needsRender = it },
    ) { scene -> _boardScene = scene }

    override fun attach(surface: Chess3DSurface) {}
    override fun detach() {}

    override fun updatePosition(fen: String) = updatePosition(fen, null)

    override fun updatePosition(fen: String, transition: Board3DTransition?) {
        driver.setPosition(runCatching { Board3DSceneMapper.fromFen(fen) }.getOrNull(), transition)
    }

    override fun onUserInteraction(event: Board3DInput) {
        when (event) {
            // Camera-only changes publish no scene, so they mark the driver dirty directly. During a
            // drag this runs once per touch event: a timestamp write, no job churn on the main
            // thread at exactly the moment smoothness matters.
            is Board3DInput.SetCamera -> {
                _cameraParams = event.camera
                driver.markDirty()
            }
            is Board3DInput.Resize -> {
                if (event.heightPx > 0) {
                    _cameraParams = _cameraParams.copy(aspect = event.widthPx.toFloat() / event.heightPx.toFloat())
                    driver.markDirty()
                }
            }
            else -> Unit
        }
    }

    override fun setSelectedSquare(square: BoardSquare?) {
        driver.setSelected(square)
    }

    override fun setHighlightedSquares(squares: List<HighlightedSquare>) {
        driver.setHighlighted(squares)
    }

    override fun dispose() {
        driver.cancel()
        animScope.cancel()
    }
}
