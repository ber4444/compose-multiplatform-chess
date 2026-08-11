package com.example.myapplication.board3d

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    private var _isAnimating by mutableStateOf(false)
    private var _isInteracting by mutableStateOf(false)
    val needsRender: Boolean get() = _isAnimating || _isInteracting

    private val animScope = CoroutineScope(Dispatchers.Main)
    private val driver = Board3DAnimationDriver(animScope, onAnimationStateChanged = { _isAnimating = it }) { scene -> _boardScene = scene }
    private var interactJob: kotlinx.coroutines.Job? = null

    override fun attach(surface: Chess3DSurface) {}
    override fun detach() {}

    override fun updatePosition(fen: String) = updatePosition(fen, null)

    override fun updatePosition(fen: String, transition: Board3DTransition?) {
        driver.setPosition(runCatching { Board3DSceneMapper.fromFen(fen) }.getOrNull(), transition)
    }

    override fun onUserInteraction(event: Board3DInput) {
        when (event) {
            is Board3DInput.SetCamera -> {
                _cameraParams = event.camera
                _isInteracting = true
                interactJob?.cancel()
                interactJob = animScope.launch {
                    kotlinx.coroutines.delay(200)
                    _isInteracting = false
                }
            }
            is Board3DInput.Resize -> {
                if (event.heightPx > 0) {
                    _cameraParams = _cameraParams.copy(aspect = event.widthPx.toFloat() / event.heightPx.toFloat())
                }
            }
            else -> Unit
        }
    }

    override fun setSelectedSquare(square: BoardSquare?) {
        driver.setSelected(square)
    }

    override fun setHighlightedSquares(squares: List<BoardSquare>) {
        driver.setHighlighted(squares)
    }

    override fun dispose() {
        interactJob?.cancel()
        driver.cancel()
        animScope.cancel()
    }
}
