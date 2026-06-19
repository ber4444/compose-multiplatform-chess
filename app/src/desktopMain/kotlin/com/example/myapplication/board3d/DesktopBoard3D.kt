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
                    // Default = Vulkan (LWJGL headless). Set CHESS_DESKTOP_RENDERER=wgpu to fall
                    // back to the wgpu4k renderer (kept as an opt-in escape hatch / A-B comparison).
                    when (System.getenv("CHESS_DESKTOP_RENDERER")?.trim()?.lowercase()) {
                        "wgpu", "webgpu" -> DesktopWgpuChessRenderer(bytes)
                        else -> VulkanChessRenderer(bytes)
                    }
                }.getOrNull()
            }
        },
        surfaceContent = { renderer, modifier ->
            DesktopBoard3DSurface(renderer, modifier)
        }
    )
}
