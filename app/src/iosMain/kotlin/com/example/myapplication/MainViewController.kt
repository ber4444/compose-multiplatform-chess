package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIViewController
import com.example.myapplication.ui.theme.MyApplicationTheme
import androidx.compose.ui.unit.dp
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.CurrentGameStore
import com.example.myapplication.persistence.CurrentGameStoreSupport
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.asSnapshotSink
import com.example.myapplication.persistence.createSettings
import com.example.myapplication.share.iosPgnSharer

/**
 * iOS entry point. The engine is created and started on the Swift side
 * (StockfishChessEngine) and injected here, mirroring desktop Main.kt.
 * Pass null to play against the built-in CPU.
 *
 * The Move Coach uses DeterministicCoach. Rules Q&A uses the Foundation Models
 * provider registered by the Swift app when that provider is available.
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
    val appSettings = remember { AppSettings(settings) }
    val gameHistory = remember { GameHistoryRepository(settings) }
    val pgnSharer = remember { iosPgnSharer() }
    val viewModel = remember {
        GameViewModel(
            restoredState.state,
            snapshotSink = currentGameStore.asSnapshotSink(),
            initialShow3D = appSettings.board3DEnabled.value,
            initialEngineDifficulty = appSettings.engineDifficulty.value,
        )
    }
    val moveCoachManager = remember {
        com.example.myapplication.movecoach.MoveCoachManager(
            gameViewModel = viewModel,
            engineDifficultyName = appSettings.engineDifficulty.value.name
        )
    }
    // Platform.isDebugBinary is the K/N equivalent of Android's FLAG_DEBUGGABLE, and what
    // AppRoot's `isDebug` reads.
    // The opt-in is scoped to this expression rather than the file or the module: the API is
    // experimental, and a module-wide opt-in would silently cover future uses that nobody
    // reviewed. There is no stable equivalent — Kotlin/Native exposes no other build-type probe.
    val debug = remember {
        @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
        kotlin.native.Platform.isDebugBinary
    }
    DisposableEffect(Unit) {
        viewModel.attachEngine(engine)

        // Testability hook for the simulator screenshot harness (tools/ios_3d_screenshot.sh): start
        // directly on the 3D board so it can be captured without a human tapping the toggle.
        if (platform.posix.getenv("CHESS_START_3D") != null) viewModel.setShow3D(true)
        
        var backfiller: com.example.myapplication.persistence.GameHistoryBackfiller? = null
        if (engine != null) {
            backfiller = com.example.myapplication.persistence.GameHistoryBackfiller(gameHistory, engine, viewModel.soloPaused)
            backfiller.start()
        }
        
        onDispose {
            backfiller?.stop()
            moveCoachManager.close()
            viewModel.close() // also closes the attached engine and cancels coach job
        }
    }
    MyApplicationTheme {
        AppRoot(
            viewModel = viewModel,
            settings = appSettings,
            board3D = remember { com.example.myapplication.board3d.iosBoard3DSupport(filamentFactory) },
            gameHistory = gameHistory,
            pgnSharer = pgnSharer,
            moveCoachManager = moveCoachManager,
            switchTopPadding = (-16).dp,
            isDebug = debug,
        )
    }
}
