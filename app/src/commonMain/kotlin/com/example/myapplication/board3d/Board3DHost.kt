package com.example.myapplication.board3d

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

        val inputModifier = modifier
            .testTag("board_3d")
            .onSizeChanged { size ->
                if (size.height > 0) {
                    cameraController.onResize(size.width.toFloat() / size.height.toFloat())
                    // Propagate updated aspect immediately so renderers don't stay at the
                    // initial aspect=1 until the user drags.
                    currentRenderer.onUserInteraction(Board3DInput.SetCamera(cameraController.camera))
                }
            }
            .pointerInput(currentRenderer) {
                detectTapGestures { offset ->
                    // Tap -> ray pick -> board square. Picking is pure common code (camera + math);
                    // the scene lets it pick a tall piece over the empty square its top projects onto.
                    // The resulting square is routed back to Compose (selection/move stay there).
                    val xNorm = offset.x / size.width.toFloat()
                    val yNorm = offset.y / size.height.toFloat()
                    val ray = CameraMath.rayFromScreen(cameraController.camera, xNorm, yNorm)
                    BoardRayPicker.pickSquare(ray, previousScene)?.let { currentOnTap(it) }
                }
            }
            .pointerInput(currentRenderer) {
                // One detector for both orbit (single-finger pan) and zoom (pinch). Splitting drag
                // and transform into separate pointerInput blocks made detectTransformGestures
                // swallow single-finger drags as no-op zoom=1 gestures, so the board never orbited.
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) cameraController.onZoom(1f / zoom)
                    if (pan != Offset.Zero) {
                        cameraController.onDrag(pan.x / size.width.toFloat(), pan.y / size.height.toFloat())
                    }
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
