package com.example.myapplication.board3d

import game.app.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

@OptIn(ExperimentalResourceApi::class)
fun desktopBoard3DSupport(): Board3DSupport {
    return Board3DSupport(
        rendererFactory = {
            runCatching {
                val bytes = Res.readBytes("files/models/chess.glb")
                VulkanChessRenderer(bytes)
            }.getOrNull()
        },
        surfaceContent = { renderer, modifier ->
            DesktopBoard3DSurface(renderer, modifier)
        }
    )
}
