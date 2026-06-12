package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import com.example.myapplication.ui.theme.MyApplicationTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import co.touchlab.kermit.Logger

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.title = "Chess"
    ComposeViewport("ComposeTarget") {
        val viewModel = remember { GameViewModel() }
        LaunchedEffect(Unit) {
            val engine = WasmStockfishEngine()
            if (engine.start()) {
                viewModel.attachEngine(engine)   // flips "Stockfish: on"; viewModel now owns engine.close()
            } else {
                Logger.w("Main") { "Stockfish wasm worker failed to start; using CPU fallback" }
                engine.close()
            }
        }
        DisposableEffect(Unit) {
            onDispose { viewModel.close() }
        }

        MyApplicationTheme(darkTheme = false) {
            ChessApp(viewModel = viewModel)
        }
    }
}
