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
import com.example.ondeviceai.initializeCactus
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
     * Attach the on-device move coach using Cactus (llama.cpp).
     * Cactus downloads the model from Hugging Face on first launch (~200 MB for
     * gemma3-270m) and caches it locally. Subsequent launches use the cached
     * model (~1-2s init). This replaces the earlier LiteRT-LM path (557 MB,
     * 7-9s cold start) and ML Kit Prompt API (AICore, narrow device support).
     */
    private fun attachMoveCoach(isDebug: Boolean) {
        if (!isDebug) {
            holder.gameViewModel.attachCoachOrchestrator(null)
            return
        }

        // Initialize Cactus native runtime (required before any CactusLM use)
        initializeCactus(this)

        holder.gameViewModel.setCoachModelState(
            com.example.myapplication.movecoach.MoveCoachUiState.LoadingModel(
                message = "Downloading Gemma 270M model (first launch only)…"
            )
        )

        CoroutineScope(Dispatchers.IO).launch {
            val factory = defaultOnDeviceTextGeneratorFactory()
            val generator = factory.create()
            // Pre-initialize: download model (first launch) + load into memory
            runCatching { generator?.warmup() }

            holder.gameViewModel.setCoachModelState(
                com.example.myapplication.movecoach.MoveCoachUiState.LoadingModel(
                    message = "Starting Gemma engine…"
                )
            )

            holder.gameViewModel.attachCoachOrchestrator(
                DefaultAiCoachOrchestrator(
                    factory = factory,
                    contextProvider = {
                        com.example.ondeviceai.AiContextSnapshot(
                            isDeviceModelAvailable = true,
                            isAppForegrounded = holder.isForeground,
                            userSetting = com.example.ondeviceai.AiUserSetting.OFFLINE_ONLY,
                        )
                    },
                )
            )
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
