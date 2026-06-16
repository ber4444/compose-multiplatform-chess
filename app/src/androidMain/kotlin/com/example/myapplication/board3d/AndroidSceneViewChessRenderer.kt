package com.example.myapplication.board3d

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Compose-observable state holder implementing [Chess3DBoardRenderer] for the SceneView backend.
 *
 * SceneView owns the Filament Engine / render lifecycle through its composable, so
 * [attach], [detach], and [dispose] are all no-ops here.  All mutable state is exposed as
 * [mutableStateOf] snapshot state so that [AndroidBoard3DSurface] recomposes automatically
 * whenever the position, camera, or selection changes.
 */
class AndroidSceneViewChessRenderer(
    val glbBytes: ByteArray,
) : Chess3DBoardRenderer {

    private var _boardScene by mutableStateOf<Board3DScene?>(null)
    val boardScene: Board3DScene? get() = _boardScene

    private var _cameraParams by mutableStateOf(OrbitCameraController.DEFAULT_WHITE_VIEW)
    val cameraParams: CameraParams get() = _cameraParams

    private var _selectedSquare by mutableStateOf<BoardSquare?>(null)
    val selectedSquare: BoardSquare? get() = _selectedSquare

    override fun attach(surface: Chess3DSurface) {}
    override fun detach() {}

    override fun updatePosition(fen: String) {
        _boardScene = runCatching { Board3DSceneMapper.fromFen(fen) }.getOrNull()
    }

    override fun onUserInteraction(event: Board3DInput) {
        when (event) {
            is Board3DInput.SetCamera -> _cameraParams = event.camera
            is Board3DInput.Resize -> {
                if (event.heightPx > 0) {
                    _cameraParams = _cameraParams.copy(aspect = event.widthPx.toFloat() / event.heightPx.toFloat())
                }
            }
            else -> Unit
        }
    }

    override fun setSelectedSquare(square: BoardSquare?) {
        _selectedSquare = square
    }

    override fun dispose() {}
}
