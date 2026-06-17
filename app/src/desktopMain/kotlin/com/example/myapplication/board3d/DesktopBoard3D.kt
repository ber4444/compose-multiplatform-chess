package com.example.myapplication.board3d

import game.app.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

@OptIn(ExperimentalResourceApi::class)
fun desktopBoard3DSupport(): Board3DSupport {
    return Board3DSupport(
        rendererFactory = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    val bytes = Res.readBytes("files/models/chess.glb")
                    DesktopWgpuChessRenderer(bytes)
                }.getOrNull()
            }
        },
        surfaceContent = { renderer, modifier ->
            DesktopBoard3DSurface(renderer, modifier)
        }
    )
}
