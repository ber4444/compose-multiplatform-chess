package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import com.example.myapplication.ui.theme.MyApplicationTheme

/**
 * iOS entry point. The engine is created and started on the Swift side
 * (StockfishChessEngine) and injected here, mirroring desktop Main.kt.
 * Pass null to play against the built-in CPU.
 */
fun MainViewController(engine: ChessEngine?): UIViewController = ComposeUIViewController {
    val viewModel = remember { GameViewModel() }
    DisposableEffect(Unit) {
        viewModel.attachEngine(engine)
        onDispose { viewModel.close() } // also closes the attached engine
    }
    MyApplicationTheme { ChessApp(viewModel = viewModel) }
}
