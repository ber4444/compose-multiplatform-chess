package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIViewController
import androidx.compose.ui.unit.dp
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.CurrentGameStore
import com.example.myapplication.persistence.CurrentGameStoreSupport
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.createSettings
import com.example.myapplication.share.iosPgnSharer

/**
 * iOS entry point. The engine is created and started on the Swift side
 * (StockfishChessEngine) and injected here, mirroring desktop Main.kt.
 * Pass null to play against the built-in CPU.
 *
 * [filamentFactory] is implemented by the Swift app target and hosts the Metal-native Filament
 * renderer. Keeping it injected mirrors the Stockfish engine bridge while leaving the Kotlin
 * framework independent from Filament's C++ xcframeworks.
 */
@OptIn(ExperimentalForeignApi::class)
fun MainViewController(
    engine: ChessEngine?,
    filamentFactory: com.example.myapplication.board3d.FilamentChessViewFactory,
): UIViewController = ComposeUIViewController {
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
    val gameHistory = remember { GameHistoryRepository(settings) }
    val pgnSharer = remember { iosPgnSharer() }
    DisposableEffect(Unit) {
        viewModel.attachEngine(engine)
        if (platform.posix.getenv("CHESS_START_3D") != null) viewModel.setShow3D(true)
        onDispose { viewModel.close() }
    }
    AppRoot(
        viewModel = viewModel,
        settings = appSettings,
        board3D = remember { com.example.myapplication.board3d.iosBoard3DSupport(filamentFactory) },
        gameHistory = gameHistory,
        pgnSharer = pgnSharer,
        switchTopPadding = (-16).dp
    )
}
