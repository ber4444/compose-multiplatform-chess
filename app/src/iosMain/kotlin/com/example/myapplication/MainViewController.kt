package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIViewController
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.ondeviceai.DefaultAiCoachOrchestrator
import com.example.ondeviceai.defaultOnDeviceTextGeneratorFactory
import androidx.compose.ui.unit.dp

/**
 * iOS entry point. The engine is created and started on the Swift side
 * (StockfishChessEngine) and injected here, mirroring desktop Main.kt.
 * Pass null to play against the built-in CPU.
 *
 * The move-coach orchestrator is constructed here using the iOS default factory,
 * which queries [com.example.ondeviceai.FoundationModelsBridgeRegistry]. The
 * Swift side registers a Foundation Models provider in iOSApp.swift init before
 * this runs; pre-iOS-26 devices report unavailable and fall back deterministically.
 */
@OptIn(ExperimentalForeignApi::class)
fun MainViewController(engine: ChessEngine?): UIViewController = ComposeUIViewController {
    val viewModel = remember { GameViewModel() }
    DisposableEffect(Unit) {
        viewModel.attachEngine(engine)
        viewModel.attachCoachOrchestrator(
            DefaultAiCoachOrchestrator(factory = defaultOnDeviceTextGeneratorFactory())
        )
        // Testability hook for the simulator screenshot harness (tools/ios_3d_screenshot.sh): start
        // directly on the 3D board so it can be captured without a human tapping the toggle.
        if (platform.posix.getenv("CHESS_START_3D") != null) viewModel.setShow3D(true)
        onDispose {
            viewModel.close() // also closes the attached engine and cancels coach job
        }
    }
    MyApplicationTheme { ChessApp(viewModel = viewModel, board3D = remember { com.example.myapplication.board3d.iosBoard3DSupport() }, switchTopPadding = (-16).dp) }
}
