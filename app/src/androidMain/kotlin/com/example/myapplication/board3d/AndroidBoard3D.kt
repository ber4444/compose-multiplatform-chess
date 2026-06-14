package com.example.myapplication.board3d

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import game.app.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

class AndroidChess3DSurface(
    val holder: SurfaceHolder,
    override val widthPx: Int,
    override val heightPx: Int
) : Chess3DSurface

@Composable
fun AndroidBoard3DSurface(renderer: Chess3DBoardRenderer, modifier: Modifier) {
    // The shared Board3DHost wires the tap/drag/zoom gesture detectors (and the board_3d testTag)
    // into [modifier]. A SurfaceView punches a hole in the window and is non-interactive, so we host
    // it inside a plain Compose Box that carries those gestures: touches the SurfaceView doesn't
    // consume bubble up to the Box, giving us tap-to-move and orbit-drag without any AndroidView
    // touch-interop quirks. This mirrors the iOS surface's `interactive = false`.
    Box(modifier) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    isClickable = false
                    isFocusable = false
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            renderer.attach(AndroidChess3DSurface(holder, width, height))
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            w: Int,
                            h: Int
                        ) {
                            renderer.onUserInteraction(Board3DInput.Resize(w, h))
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            renderer.detach()
                        }
                    })
                }
            },
            modifier = Modifier.matchParentSize()
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
fun androidBoard3DSupport(): Board3DSupport {
    return Board3DSupport(
        rendererFactory = {
            runCatching {
                val bytes = Res.readBytes("files/models/chess.glb")
                AndroidVulkanChessRenderer(bytes)
            }.getOrNull()
        },
        surfaceContent = { renderer, modifier ->
            AndroidBoard3DSurface(renderer, modifier)
        }
    )
}
