package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import androidx.compose.ui.ExperimentalComposeUiApi
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.createSettings
import co.touchlab.kermit.Logger

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.title = "Chess"
    ComposeViewport("ComposeTarget") {
        val viewModel = remember { GameViewModel() }
        val appSettings = remember { AppSettings(createSettings("chess")) }
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

        AppRoot(
            viewModel = viewModel,
            settings = appSettings,
            board3D = com.example.myapplication.board3d.wasmBoard3DSupport(viewModel)
        )
    }
}
