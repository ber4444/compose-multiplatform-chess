package com.example.myapplication.board3d

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.example.myapplication.ChessLoader

import androidx.compose.ui.layout.onSizeChanged

@Composable
fun Board3D(
    support: Board3DSupport,
    fen: String,
    modifier: Modifier = Modifier,
    onUnavailable: () -> Unit,
    cameraSession: Board3DSessionState = remember { Board3DSessionState() },
    onRendererReady: () -> Unit = {},
    selectedSquare: BoardSquare? = null,
    onSquareTapped: (BoardSquare) -> Unit = {},
) {
    var renderer by remember { mutableStateOf<Chess3DBoardRenderer?>(null) }
    var initAttempted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val created = support.rendererFactory.create()
        if (created == null) {
            onUnavailable()
        } else {
            renderer = created
        }
        initAttempted = true
    }

    val currentRenderer = renderer

    if (currentRenderer == null) {
        if (!initAttempted) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .testTag("board_3d_loading"),
                contentAlignment = Alignment.Center
            ) {
                ChessLoader("Loading 3D Engine")
            }
        }
    } else {
        val currentOnTap by rememberUpdatedState(onSquareTapped)
        val currentOnRendererReady by rememberUpdatedState(onRendererReady)
        var previousScene by remember { mutableStateOf<Board3DScene?>(null) }

        // Position (FEN) and selection are pushed to the renderer as they change.
        LaunchedEffect(currentRenderer, fen) {
            val nextScene = Board3DSceneMapper.fromFen(fen)
            val transition = if (previousScene != null) {
                Board3DSceneDiffer.diff(previousScene!!, nextScene)
            } else {
                null
            }
            previousScene = nextScene
            currentRenderer.updatePosition(fen, transition)
        }
        LaunchedEffect(currentRenderer, selectedSquare) { currentRenderer.setSelectedSquare(selectedSquare) }

        // Push the initial camera once.
        LaunchedEffect(currentRenderer) {
            currentRenderer.onUserInteraction(Board3DInput.SetCamera(cameraSession.cameraForRenderer()))
            currentOnRendererReady()
        }

        val inputModifier = modifier
            .testTag("board_3d")
            .onSizeChanged { size ->
                if (size.width > 1 && size.height > 1) {
                    cameraSession.onResize(size.width.toFloat() / size.height.toFloat())
                    currentRenderer.onUserInteraction(Board3DInput.SetCamera(cameraSession.cameraForRenderer()))
                }
            }
            .pointerInput(currentRenderer) {
                detectTapGestures { offset ->
                    val xNorm = offset.x / size.width.toFloat()
                    val yNorm = offset.y / size.height.toFloat()
                    val ray = CameraMath.rayFromScreen(cameraSession.camera, xNorm, yNorm)
                    BoardRayPicker.pickSquare(ray, previousScene)?.let { currentOnTap(it) }
                }
            }
            .pointerInput(currentRenderer) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f || pan != Offset.Zero) {
                        if (zoom != 1f && zoom > 0.5f && zoom < 2.0f) cameraSession.onZoom(1f / zoom)
                        if (pan != Offset.Zero) {
                            cameraSession.onDrag(pan.x / size.width.toFloat(), pan.y / size.height.toFloat())
                        }
                        currentRenderer.onUserInteraction(Board3DInput.SetCamera(cameraSession.cameraForRenderer()))
                    }
                }
            }

        support.surfaceContent(currentRenderer, inputModifier)

        DisposableEffect(currentRenderer) {
            onDispose {
                currentRenderer.detach()
                currentRenderer.dispose()
            }
        }
    }
}
