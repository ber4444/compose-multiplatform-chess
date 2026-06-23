package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIViewController
import com.example.myapplication.ui.theme.MyApplicationTheme
import androidx.compose.ui.unit.dp

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
    val viewModel = remember { GameViewModel() }
    DisposableEffect(Unit) {
        viewModel.attachEngine(engine)
        if (platform.posix.getenv("CHESS_START_3D") != null) viewModel.setShow3D(true)
        onDispose { viewModel.close() }
    }
    MyApplicationTheme {
        ChessApp(
            viewModel = viewModel,
            board3D = remember { com.example.myapplication.board3d.iosBoard3DSupport(filamentFactory) },
            switchTopPadding = (-16).dp,
        )
    }
}
