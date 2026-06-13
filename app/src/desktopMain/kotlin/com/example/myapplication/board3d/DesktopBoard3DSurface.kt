package com.example.myapplication.board3d

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged

@Composable
fun DesktopBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier = Modifier) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var surfaceSize by remember { mutableStateOf(Pair(1, 1)) }
    
    val surface = remember(surfaceSize) {
        ImageBitmapChess3DSurface(surfaceSize.first, surfaceSize.second) { newFrame ->
            frame = newFrame
        }
    }

    DisposableEffect(renderer, surface) {
        renderer.attach(surface)
        onDispose {
            renderer.detach()
        }
    }

    Box(
        modifier = modifier.onSizeChanged { size ->
            if (size.width > 0 && size.height > 0) {
                if (surfaceSize.first != size.width || surfaceSize.second != size.height) {
                    surfaceSize = Pair(size.width, size.height)
                    renderer.onUserInteraction(Board3DInput.Resize(size.width, size.height))
                }
            }
        }
    ) {
        frame?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = "3D Chess Board",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
