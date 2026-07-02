package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.unit.dp
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.CurrentGameStore
import com.example.myapplication.persistence.CurrentGameStoreSupport
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.createSettings
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.myapplication.board3d.desktopBoard3DSupport
import com.example.myapplication.share.desktopPgnSharer

fun main() = application {
    // One Settings backing store shared by AppSettings and the autosave store (Phase 2). `remember`
    // so a recomposition doesn't re-read disk mid-session.
    val settings = remember { createSettings("chess") }
    // Autosave + resume-later: load any saved game before constructing the VM so it seeds from it.
    val currentGameStore = remember { CurrentGameStore(settings) }
    val restoredState = remember { CurrentGameStoreSupport.loadInitialState(currentGameStore) }
    DisposableEffect(Unit) {
        if (restoredState.shouldClear) currentGameStore.clear()
        onDispose { }
    }
    val viewModel = remember { GameViewModel(restoredState.state, currentGameStore) }
    val board3D = remember { desktopBoard3DSupport() }
    val appSettings = remember { AppSettings(settings) }
    val gameHistory = remember { GameHistoryRepository(settings) }
    val pgnSharer = remember { desktopPgnSharer() }

    DisposableEffect(Unit) {
        val engine = DesktopStockfishEngine()
        CoroutineScope(Dispatchers.IO).launch {
            if (engine.start()) {
                viewModel.attachEngine(engine)
            } else {
                Logger.w("Main") { "Failed to start stockfish." }
            }
        }
        onDispose {
            engine.close()
            viewModel.close()
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Chess",
        state = WindowState(width = 800.dp, height = 900.dp)
    ) {
        AppRoot(
            viewModel = viewModel,
            settings = appSettings,
            board3D = board3D,
            gameHistory = gameHistory,
            pgnSharer = pgnSharer,
        )
    }
}
