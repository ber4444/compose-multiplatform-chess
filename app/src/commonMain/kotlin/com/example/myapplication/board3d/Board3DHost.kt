package com.example.myapplication.board3d

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag

import androidx.compose.ui.layout.onSizeChanged

@Composable
fun Board3D(
    support: Board3DSupport,
    fen: String,
    modifier: Modifier = Modifier,
    onUnavailable: () -> Unit,
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

    if (initAttempted && currentRenderer != null) {
        val cameraController = remember { OrbitCameraController(1f) }
        val currentOnTap by rememberUpdatedState(onSquareTapped)

        // Position (FEN) and selection are pushed to the renderer as they change.
        LaunchedEffect(currentRenderer, fen) { currentRenderer.updatePosition(fen) }
        LaunchedEffect(currentRenderer, selectedSquare) { currentRenderer.setSelectedSquare(selectedSquare) }

        val inputModifier = modifier
            .testTag("board_3d")
            .onSizeChanged { size ->
                if (size.height > 0) {
                    cameraController.onResize(size.width.toFloat() / size.height.toFloat())
                }
            }
            .pointerInput(currentRenderer) {
                detectTapGestures { offset ->
                    // Tap -> ray pick -> board square. Picking is pure common code (camera + math);
                    // the resulting square is routed back to Compose (selection/move stay there).
                    val xNorm = offset.x / size.width.toFloat()
                    val yNorm = offset.y / size.height.toFloat()
                    val ray = CameraMath.rayFromScreen(cameraController.camera, xNorm, yNorm)
                    BoardRayPicker.pickSquare(ray)?.let { currentOnTap(it) }
                }
            }
            .pointerInput(currentRenderer) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val dxNorm = dragAmount.x / size.width.toFloat()
                    val dyNorm = dragAmount.y / size.height.toFloat()
                    cameraController.onDrag(dxNorm, dyNorm)
                    currentRenderer.onUserInteraction(Board3DInput.SetCamera(cameraController.camera))
                }
            }
            .pointerInput(currentRenderer) {
                detectTransformGestures { _, _, zoom, _ ->
                    cameraController.onZoom(1f / zoom)
                    currentRenderer.onUserInteraction(Board3DInput.SetCamera(cameraController.camera))
                }
            }

        // Push the initial camera once.
        LaunchedEffect(currentRenderer) {
            currentRenderer.onUserInteraction(Board3DInput.SetCamera(cameraController.camera))
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
