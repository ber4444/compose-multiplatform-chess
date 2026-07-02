package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import androidx.compose.ui.ExperimentalComposeUiApi
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.CurrentGameStore
import com.example.myapplication.persistence.CurrentGameStoreSupport
import com.example.myapplication.persistence.createSettings
import co.touchlab.kermit.Logger

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.title = "Chess"
    ComposeViewport("ComposeTarget") {
        // One Settings backing store shared by AppSettings and the autosave store (Phase 2).
        val settings = remember { createSettings("chess") }
        val currentGameStore = remember { CurrentGameStore(settings) }
        val restoredState = remember { CurrentGameStoreSupport.loadInitialState(currentGameStore) }
        DisposableEffect(Unit) {
            if (restoredState.shouldClear) currentGameStore.clear()
            onDispose { }
        }
        val viewModel = remember { GameViewModel(restoredState.state, currentGameStore) }
        val appSettings = remember { AppSettings(settings) }
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
