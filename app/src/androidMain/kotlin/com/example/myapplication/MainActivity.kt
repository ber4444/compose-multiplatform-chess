package com.example.myapplication

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme
import android.content.pm.ApplicationInfo
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

class MainActivity : ComponentActivity() {
    private val holder: AndroidGameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebug) {
            Logger.setMinSeverity(Severity.Assert)
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(SYSTEM_BAR_SCRIM),
            navigationBarStyle = SystemBarStyle.dark(SYSTEM_BAR_SCRIM)
        )

        holder.gameViewModel.attachEngine(createStockfishEngine())

        setContent {
            MyApplicationTheme {
                ChessApp(
                    viewModel = holder.gameViewModel,
                    board3D = androidx.compose.runtime.remember { com.example.myapplication.board3d.androidBoard3DSupport() }
                )
            }
        }
    }

    private fun createStockfishEngine(): ChessEngine? {
        val engine = StockfishEngine(
            nativeLibraryDir = applicationInfo.nativeLibraryDir,
            filesDir = filesDir,
            assetManager = assets,
            supportedAbis = Build.SUPPORTED_ABIS
        )
        return if (engine.isAvailable() && engine.start()) {
            Logger.i("MainActivity") { "Stockfish engine initialized successfully" }
            engine
        } else {
            Logger.w("MainActivity") { "Stockfish engine is unavailable" }
            null
        }
    }

    private companion object {
        private const val SYSTEM_BAR_SCRIM = 0x66000000
    }
}

class AndroidGameViewModel : ViewModel() {
    val gameViewModel = GameViewModel()

    override fun onCleared() {
        super.onCleared()
        gameViewModel.close()
    }
}
