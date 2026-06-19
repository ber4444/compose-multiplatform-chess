package com.example.myapplication.board3d

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import game.app.generated.resources.Res
import kotlinx.browser.document
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLCanvasElement
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Phase A.2 web baseline capture UI. Hosts the production [WebGpuChessRenderer] directly against a
 * dedicated `<canvas id="baseline-canvas">`, with a row of buttons — one per [VisualBaselineScene] —
 * plus a "Download PNG" action that grabs the current canvas pixels via the standard browser
 * `toDataURL` path. This exercises the same renderer users see in production, at the canonical
 * baseline resolution, so captures are directly comparable to `app/build/baseline/desktop/` and
 * `app/build/baseline/ios/`.
 *
 * Shown in place of [ChessApp] when the URL hash is `#baseline`; see `Main.kt`. The capture has to
 * run inside the running wasm app because WebGPU contexts cannot be driven headlessly from JS, and
 * because the production renderer's geometry/material pipeline is what we actually want to measure.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun WebBaselineCapture() {
    var renderer by remember { mutableStateOf<WebGpuChessRenderer?>(null) }
    var currentScene by remember { mutableStateOf(VisualBaselineScenes.START_POSITION_HIGH_LIGHTING) }
    var status by remember { mutableStateOf("Initializing WebGPU…") }

    DisposableEffect(Unit) {
        val canvas = (document.createElement("canvas") as HTMLCanvasElement).apply {
            id = "baseline-canvas"
            width = VisualBaselineScenes.DEFAULT_WIDTH_PX
            height = VisualBaselineScenes.DEFAULT_HEIGHT_PX
            style.setProperty("display", "block")
            style.setProperty("width", "${VisualBaselineScenes.DEFAULT_WIDTH_PX / 2}px")
            style.setProperty("height", "${VisualBaselineScenes.DEFAULT_HEIGHT_PX / 2}px")
            style.setProperty("background", "#000")
        }
        document.body?.appendChild(canvas)

        val scope = kotlinx.coroutines.MainScope()
        scope.launch {
            val gpu = getNavigatorGpu()
            if (gpu == null) {
                status = "WebGPU unavailable in this browser."
                return@launch
            }
            val adapter = kotlinx.coroutines.withTimeoutOrNull(2000) { awaitPromiseSafe(requestAdapterJs(gpu)) }
            if (adapter == null) {
                status = "No WebGPU adapter."
                return@launch
            }
            val glb = kotlinx.coroutines.withTimeoutOrNull(5000) { Res.readBytes("files/models/chess.glb") }
            if (glb == null) {
                status = "chess.glb asset missing."
                return@launch
            }
            val r = WebGpuChessRenderer(glb)
            r.attach(WasmChess3DSurface(canvas, canvas.width, canvas.height))
            r.updatePosition(currentScene.fen)
            r.onUserInteraction(Board3DInput.SetCamera(currentScene.camera))
            r.onUserInteraction(
                Board3DInput.Resize(VisualBaselineScenes.DEFAULT_WIDTH_PX, VisualBaselineScenes.DEFAULT_HEIGHT_PX)
            )
            renderer = r
            status = "Ready: ${currentScene.label}"
        }

        onDispose {
            scope.cancel()
            renderer?.detach()
            renderer?.dispose()
            canvas.remove()
        }
    }

    Column(Modifier.padding(16.dp)) {
        Text("Chess 3D Baseline Capture (web)", Modifier.padding(bottom = 8.dp))
        Text(status, Modifier.padding(bottom = 12.dp))

        Row(Modifier.padding(bottom = 8.dp)) {
            VisualBaselineScenes.ALL.forEach { scene ->
                Button(
                    onClick = {
                        currentScene = scene
                        renderer?.updatePosition(scene.fen)
                        renderer?.onUserInteraction(Board3DInput.SetCamera(scene.camera))
                        status = "Captured view: ${scene.label}"
                    },
                    modifier = Modifier.padding(end = 8.dp),
                ) { Text(scene.id) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val canvas = document.getElementById("baseline-canvas") as? HTMLCanvasElement
                if (canvas == null) {
                    status = "Canvas not found."
                    return@Button
                }
                val filename = "${VisualBaselineScenes.baseName(currentScene, "web")}.png"
                downloadCanvasAsPng(canvas, filename)
                status = "Saved $filename (check Downloads)."
            }) { Text("Download PNG") }
            Text(
                "  (${currentScene.label})",
                modifier = Modifier.padding(start = 8.dp),
                color = Color(0xFF666666),
            )
        }

        // Live-capture-all: programmatically iterate scenes and download each. Useful for one-click
        // capture runs that mirror the desktop/iOS batch tests.
        Button(
            onClick = {
                status = "Capture-all: see browser downloads (one PNG per scene)."
                captureAllScenesSequentially(renderer)
            },
            modifier = Modifier.padding(top = 12.dp),
        ) { Text("Download all scenes") }
    }
}

/** Drive the renderer through every scene with a small stabilization delay between downloads. */
private fun captureAllScenesSequentially(renderer: WebGpuChessRenderer?) {
    val canvas = document.getElementById("baseline-canvas") as? HTMLCanvasElement ?: return
    val r = renderer ?: return
    val scope = kotlinx.coroutines.MainScope()
    scope.launch {
        for (scene in VisualBaselineScenes.ALL) {
            r.updatePosition(scene.fen)
            r.onUserInteraction(Board3DInput.SetCamera(scene.camera))
            // Same stabilization rationale as VisualBaselineDumpTest: give the geometry rebuild +
            // a few frames time to land before pulling pixels off the canvas.
            delay(500)
            downloadCanvasAsPng(canvas, "${VisualBaselineScenes.baseName(scene, "web")}.png")
            delay(250) // space out the browser download prompts
        }
    }
}

/**
 * Triggers a PNG download of the canvas via the standard anchor-link trick. Lives here (not in a
 * generic util) because the @JsFun signature is specific to the DOM canvas path.
 */
@JsFun("(canvas, filename) => { const a = document.createElement('a'); a.download = filename; a.href = canvas.toDataURL('image/png'); document.body.appendChild(a); a.click(); a.remove(); }")
private external fun downloadCanvasAsPng(canvas: HTMLCanvasElement, filename: String)
