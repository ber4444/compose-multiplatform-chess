package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIViewController
import androidx.compose.ui.unit.dp
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.createSettings

/**
 * iOS entry point. The engine is created and started on the Swift side
 * (StockfishChessEngine) and injected here, mirroring desktop Main.kt.
 * Pass null to play against the built-in CPU.
 */
@OptIn(ExperimentalForeignApi::class)
fun MainViewController(engine: ChessEngine?): UIViewController = ComposeUIViewController {
    val viewModel = remember { GameViewModel() }
    val appSettings = remember { AppSettings(createSettings("chess")) }
    DisposableEffect(Unit) {
        viewModel.attachEngine(engine)
        if (platform.posix.getenv("CHESS_START_3D") != null) viewModel.setShow3D(true)
        onDispose { viewModel.close() }
    }
    AppRoot(
        viewModel = viewModel,
        settings = appSettings,
        board3D = remember { com.example.myapplication.board3d.iosBoard3DSupport() },
        switchTopPadding = (-16).dp
    )
}
