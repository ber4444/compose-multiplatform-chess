package com.example.myapplication.board3d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.example.myapplication.GameViewModel
import com.example.myapplication.Set
import com.example.myapplication.WinState
import game.app.generated.resources.Res
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement

class WasmChess3DSurface(
    val canvas: HTMLCanvasElement, 
    override val widthPx: Int, 
    override val heightPx: Int
) : Chess3DSurface

internal data class CssRect(val left: Double, val top: Double, val width: Double, val height: Double)

@Suppress("UNUSED_PARAMETER")
internal fun overlayCssRect(boundsInWindow: Rect, devicePixelRatio: Double): CssRect {
    return CssRect(
        left = boundsInWindow.left.toDouble(),
        top = boundsInWindow.top.toDouble(),
        width = boundsInWindow.width.toDouble(),
        height = boundsInWindow.height.toDouble()
    )
}

internal fun overlayPhysicalSize(css: CssRect, devicePixelRatio: Double): Pair<Int, Int> {
    return Pair(
        (css.width * devicePixelRatio).toInt().coerceAtLeast(1),
        (css.height * devicePixelRatio).toInt().coerceAtLeast(1)
    )
}

@Composable
fun WasmBoard3DSurface(
    renderer: Chess3DBoardRenderer, 
    modifier: Modifier,
    viewModel: GameViewModel
) {
    val gameState by viewModel.gameState.collectAsState()
    val hideCanvas = gameState.pendingPromotion != null || 
                     gameState.winState != WinState.NONE || 
                     gameState.drawOffer == Set.BLACK

    DisposableEffect(Unit) {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.id = "board3d-overlay"
        canvas.style.setProperty("position", "absolute")
        canvas.style.setProperty("pointer-events", "none")
        document.body?.appendChild(canvas)

        onDispose {
            renderer.detach()
            canvas.remove()
        }
    }

    LaunchedEffect(hideCanvas) {
        val canvas = document.getElementById("board3d-overlay") as? HTMLCanvasElement
        if (canvas != null) {
            canvas.style.setProperty("visibility", if (hideCanvas) "hidden" else "visible")
        }
    }

    Modifier.then(modifier).onGloballyPositioned { coordinates ->
        val canvas = document.getElementById("board3d-overlay") as? HTMLCanvasElement
        if (canvas != null) {
            val bounds = coordinates.boundsInWindow()
            val dpr = window.devicePixelRatio
            val css = overlayCssRect(bounds, dpr)
            
            canvas.style.setProperty("left", "${css.left}px")
            canvas.style.setProperty("top", "${css.top}px")
            canvas.style.setProperty("width", "${css.width}px")
            canvas.style.setProperty("height", "${css.height}px")
            
            val (physicalWidth, physicalHeight) = overlayPhysicalSize(css, dpr)
            
            if (canvas.width != physicalWidth || canvas.height != physicalHeight) {
                canvas.width = physicalWidth
                canvas.height = physicalHeight
                renderer.attach(WasmChess3DSurface(canvas, physicalWidth, physicalHeight))
            }
        }
    }.let {
        androidx.compose.foundation.layout.Box(
            modifier = it.background(Color.Black.copy(alpha = 0.01f))
        )
    }
}

fun wasmBoard3DSupport(viewModel: GameViewModel): Board3DSupport? {
    if (!hasWebGpu()) return null

    return Board3DSupport(
        rendererFactory = {
            runCatching {
                val gpu = getNavigatorGpu() ?: return@runCatching null
                val adapter = kotlinx.coroutines.withTimeoutOrNull(2000) { awaitPromiseSafe(requestAdapterJs(gpu)) }
                if (adapter == null) return@runCatching null

                val glb = kotlinx.coroutines.withTimeoutOrNull(5000) { Res.readBytes("files/models/chess.glb") }
                if (glb == null) return@runCatching null
                WebGpuChessRenderer(glb)
            }.getOrNull()
        },
        surfaceContent = { renderer, modifier ->
            WasmBoard3DSurface(renderer, modifier, viewModel)
        }
    )
}
