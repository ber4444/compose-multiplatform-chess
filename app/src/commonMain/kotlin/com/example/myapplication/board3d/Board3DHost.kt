package com.example.myapplication.board3d

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag

@Composable
fun Board3D(
    support: Board3DSupport,
    fen: String,
    modifier: Modifier = Modifier,
    onUnavailable: () -> Unit,
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

        // Send FEN
        LaunchedEffect(currentRenderer, fen) {
            currentRenderer.updatePosition(fen)
        }

        // Pointer input
        val inputModifier = modifier
            .testTag("board_3d")
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // normalize by size
                    val dxNorm = dragAmount.x / size.width.toFloat()
                    val dyNorm = dragAmount.y / size.height.toFloat()
                    cameraController.onDrag(dxNorm, dyNorm)
                    currentRenderer.onUserInteraction(Board3DInput.SetCamera(cameraController.camera))
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    // zoom > 1 means zoom in (distance decrease)
                    cameraController.onZoom(1f / zoom)
                    currentRenderer.onUserInteraction(Board3DInput.SetCamera(cameraController.camera))
                }
            }

        // Ensure camera aspect is updated
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
