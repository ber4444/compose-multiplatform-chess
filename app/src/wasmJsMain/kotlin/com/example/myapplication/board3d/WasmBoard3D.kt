package com.example.myapplication.board3d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
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

internal data class OverlayCanvasStacking(
    val insertAsFirstBodyChild: Boolean,
    val pointerEvents: String,
)

internal fun overlayCanvasStacking() = OverlayCanvasStacking(
    insertAsFirstBodyChild = false,
    pointerEvents = "none",
)

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
        val stacking = overlayCanvasStacking()
        canvas.id = "board3d-overlay"
        canvas.style.setProperty("position", "absolute")
        canvas.style.setProperty("pointer-events", stacking.pointerEvents)
        // Compose renders into its own DOM canvas. Appending the Filament canvas after it made the
        // board canvas paint over Reset / Offer Draw / the 3D switch. Put the non-interactive Filament
        // canvas at the start of body so Compose remains the top visual and input layer.
        document.body?.let { body ->
            if (stacking.insertAsFirstBodyChild) body.insertBefore(canvas, body.firstChild)
            else body.appendChild(canvas)
        }

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
        // We no longer punch a hole. The Filament canvas is appended on top of Compose (last child)
        // with pointer-events: none, so it correctly blends its transparent background over the Compose UI.
        androidx.compose.foundation.layout.Box(modifier = it)
    }
}

fun wasmBoard3DSupport(viewModel: GameViewModel): Board3DSupport? {
    return Board3DSupport(
        rendererFactory = { FilamentWasmChessRenderer() },
        surfaceContent = { renderer, modifier ->
            WasmBoard3DSurface(renderer, modifier, viewModel)
        }
    )
}
