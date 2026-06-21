package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.unit.dp
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.createSettings
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.myapplication.board3d.desktopBoard3DSupport

fun main() = application {
    val viewModel = remember { GameViewModel() }
    val board3D = remember { desktopBoard3DSupport() }
    val appSettings = remember { AppSettings(createSettings("chess")) }

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
            board3D = board3D
        )
    }
}
