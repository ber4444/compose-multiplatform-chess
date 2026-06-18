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
import com.example.ondeviceai.AndroidCoachWiring
import com.example.ondeviceai.DefaultAiCoachOrchestrator
import com.example.ondeviceai.defaultOnDeviceTextGeneratorFactory
import android.content.pm.ApplicationInfo
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
     * Attach the on-device move coach (plan §8). The Android path is LiteRT-LM
     * (`com.google.ai.edge.litertlm:litertlm-android`) with a bundled Gemma
     * `.litertlm` model — no AICore / Gemini Nano dependency. The earlier
     * ML Kit Prompt API path was dropped because AICore isn't available on
     * most devices.
     *
     * Model asset: unpacked from `assets/models/gemma.litertlm` into
     * `filesDir` on first launch by [MoveCoachModelAsset]. When no model is
     * bundled (the default until you drop one in), the coach falls back to
     * deterministic rule-based text — the panel still mounts.
     *
     * The orchestrator is built off-thread because the first `Engine.initialize()`
     * call (inside the factory) can take seconds. The context snapshot reports
     * foreground state so ML Kit-style background gating stays in place if the
     * LiteRT-LM backend is ever swapped for one that needs it.
     */
    private fun attachMoveCoach(isDebug: Boolean) {
        // Gate to debug builds per plan §11 M3 "ship behind a debug flag".
        if (!isDebug) {
            holder.gameViewModel.attachCoachOrchestrator(null)
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val modelPath = MoveCoachModelAsset.ensureUnpacked(this@MainActivity)
            if (modelPath != null) {
                AndroidCoachWiring.install(
                    AndroidCoachWiring.Config(
                        modelPath = modelPath,
                        cacheDir = cacheDir.absolutePath,
                    )
                )
            }
            val orchestrator = DefaultAiCoachOrchestrator(
                factory = defaultOnDeviceTextGeneratorFactory(),
                contextProvider = {
                    com.example.ondeviceai.AiContextSnapshot(
                        isDeviceModelAvailable = modelPath != null,
                        isAppForegrounded = holder.isForeground,
                        userSetting = com.example.ondeviceai.AiUserSetting.OFFLINE_ONLY,
                    )
                },
            )
            holder.gameViewModel.attachCoachOrchestrator(orchestrator)
        }
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
