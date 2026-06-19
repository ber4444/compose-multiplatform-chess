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
 */
@OptIn(ExperimentalForeignApi::class)
fun MainViewController(engine: ChessEngine?): UIViewController = ComposeUIViewController {
    val viewModel = remember { GameViewModel() }
    DisposableEffect(Unit) {
        viewModel.attachEngine(engine)
        if (platform.posix.getenv("CHESS_START_3D") != null) viewModel.setShow3D(true)
        onDispose { viewModel.close() }
    }
    MyApplicationTheme { ChessApp(viewModel = viewModel, board3D = remember { com.example.myapplication.board3d.iosBoard3DSupport() }, switchTopPadding = (-16).dp) }
}
