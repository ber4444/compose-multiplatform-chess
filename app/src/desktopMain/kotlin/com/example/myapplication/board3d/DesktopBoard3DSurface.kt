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
import com.example.myapplication.ChessLoader
import androidx.compose.ui.Alignment

@Composable
fun DesktopBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier = Modifier) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var surfaceSize by remember { mutableStateOf(Pair(1, 1)) }
    
    val surface = remember(surfaceSize) {
        if (surfaceSize.first > 1 && surfaceSize.second > 1) {
            ImageBitmapChess3DSurface(surfaceSize.first, surfaceSize.second) { newFrame ->
                frame = newFrame
            }
        } else null
    }

    DisposableEffect(renderer, surface) {
        if (surface != null) {
            renderer.onUserInteraction(Board3DInput.Resize(surfaceSize.first, surfaceSize.second))
            renderer.attach(surface)
        }
        onDispose {
            if (surface != null) renderer.detach()
        }
    }

    Box(
        modifier = modifier.onSizeChanged { size ->
            if (size.width > 1 && size.height > 1) {
                if (surfaceSize.first != size.width || surfaceSize.second != size.height) {
                    surfaceSize = Pair(size.width, size.height)
                }
            }
        },
        contentAlignment = Alignment.Center
    ) {
        if (frame != null) {
            Image(
                bitmap = frame!!,
                contentDescription = "3D Chess Board",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            ChessLoader("Initializing Graphics")
        }
    }
}
