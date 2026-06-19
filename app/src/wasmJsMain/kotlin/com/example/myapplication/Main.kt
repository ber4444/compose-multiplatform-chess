package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import com.example.myapplication.ui.theme.MyApplicationTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import co.touchlab.kermit.Logger
import com.example.myapplication.board3d.WebBaselineCapture

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.title = "Chess"
    // Phase A.2 baseline entry point: when the URL hash is `#baseline` we render the
    // WebBaselineCapture UI instead of the normal ChessApp, so the production WebGPU renderer can
    // be driven through every VisualBaselineScene at a fixed resolution and have each canvas frame
    // downloaded as a PNG. Capture-only — the normal chess UI is one hash-change away.
    val baselineMode = window.location.hash.contains("baseline")
    ComposeViewport("ComposeTarget") {
        if (baselineMode) {
            MyApplicationTheme(darkTheme = false) { WebBaselineCapture() }
            return@ComposeViewport
        }
        val viewModel = remember { GameViewModel() }
        LaunchedEffect(Unit) {
            val engine = WasmStockfishEngine()
            if (engine.start()) {
                viewModel.attachEngine(engine)   // viewModel now owns engine.close()
            } else {
                Logger.w("Main") { "Stockfish wasm worker failed to start; using CPU fallback" }
                engine.close()
            }
        }
        DisposableEffect(Unit) {
            onDispose { viewModel.close() }
        }

        MyApplicationTheme(darkTheme = false) {
            ChessApp(
                viewModel = viewModel,
                board3D = com.example.myapplication.board3d.wasmBoard3DSupport(viewModel)
            )
        }
    }
}
