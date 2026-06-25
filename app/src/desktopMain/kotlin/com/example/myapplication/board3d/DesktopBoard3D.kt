package com.example.myapplication.board3d

import game.app.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

@OptIn(ExperimentalResourceApi::class)
fun desktopBoard3DSupport(): Board3DSupport {
    return Board3DSupport(
        rendererFactory = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    DesktopFilamentChessRenderer(
                        glb = Res.readBytes("files/models/${ChessSetConventions.GLB_ASSET}"),
                        ibl = Res.readBytes("files/env/${ChessSetConventions.IBL_ASSET}"),
                        skybox = Res.readBytes("files/env/${ChessSetConventions.SKYBOX_ASSET_BLURRED}"),
                    )
                }.getOrNull()
            }
        },
        surfaceContent = { renderer, modifier ->
            DesktopBoard3DSurface(renderer, modifier)
        }
    )
}
