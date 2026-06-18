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
import com.example.ondeviceai.DefaultAiCoachOrchestrator
import com.example.ondeviceai.defaultOnDeviceTextGeneratorFactory
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
        attachMoveCoach(isDebug)

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

    /**
     * Attach the on-device move coach (plan §8). On Android the default factory
     * prefers ML Kit Prompt API (Gemini Nano on supported devices) and falls back
     * to LiteRT-LM Gemma if the debug flag `chess.coach.litert.enabled` is set
     * AND a model is packaged (plan §6.1 / M4 spike). On devices with no local
     * model the orchestrator falls back deterministically per plan §1.4.
     *
     * The context snapshot reports isAppForegrounded from the activity lifecycle;
     * ML Kit blocks background inference so the route policy converts that to a
     * fallback automatically (plan §5).
     */
    private fun attachMoveCoach(isDebug: Boolean) {
        // Gate the coach to debug builds for the M3 ship-behind-a-flag milestone
        // (plan §11). Promoting to release waits on the §6.3 benchmark gate.
        if (!isDebug) {
            holder.gameViewModel.attachCoachOrchestrator(null)
            return
        }
        val orchestrator = DefaultAiCoachOrchestrator(
            factory = defaultOnDeviceTextGeneratorFactory(),
            contextProvider = {
                com.example.ondeviceai.AiContextSnapshot(
                    isDeviceModelAvailable = true, // The factory probes real availability per-call.
                    isAppForegrounded = holder.isForeground,
                    userSetting = com.example.ondeviceai.AiUserSetting.OFFLINE_ONLY,
                )
            },
        )
        holder.gameViewModel.attachCoachOrchestrator(orchestrator)
    }

    override fun onStart() {
        super.onStart()
        holder.isForeground = true
    }

    override fun onStop() {
        super.onStop()
        holder.isForeground = false
    }

    private companion object {
        private const val SYSTEM_BAR_SCRIM = 0x66000000
    }
}

class AndroidGameViewModel : ViewModel() {
    val gameViewModel = GameViewModel()
    @Volatile var isForeground: Boolean = true

    override fun onCleared() {
        super.onCleared()
        gameViewModel.close()
    }
}
