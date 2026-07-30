package com.example.myapplication

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import com.example.ondeviceai.DefaultAiCoachOrchestrator
import com.example.ondeviceai.DefaultGameSummaryOrchestrator
import com.example.ondeviceai.VendorRouteExecutor
import com.example.ondeviceai.VendorRoute
import com.example.ondeviceai.initializeCactus
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.CurrentGameStore
import com.example.myapplication.persistence.CurrentGameStoreSupport
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.asSnapshotSink
import com.example.myapplication.persistence.createSettings
import com.example.myapplication.share.androidPgnSharer
import android.content.pm.ApplicationInfo
import com.example.myapplication.movecoach.MoveCoachManager
import com.example.myapplication.movecoach.GameSummaryManager
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.example.myapplication.bench.runAndroidBench
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

        val appCheck = com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
        if (isDebug) {
            appCheck.installAppCheckProviderFactory(
                com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory.getInstance()
            )
        } else {
            appCheck.installAppCheckProviderFactory(
                com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance()
            )
        }

        if (isDebug && intent.hasExtra("bench_iterations")) {
            val iterations = intent.getIntExtra("bench_iterations", 1)
            // Without this, CactusTextGenerator's underlying context is never set up and every
            // run silently falls back with 0 tokens generated (getCactus()'s own comment warns
            // "otherwise this will throw" — in practice the caller swallows it into a fallback).
            initializeCactus(this)
            CoroutineScope(Dispatchers.IO).launch {
                runAndroidBench(this@MainActivity, iterations)
                finish()
            }
            return
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(SYSTEM_BAR_SCRIM),
            navigationBarStyle = SystemBarStyle.dark(SYSTEM_BAR_SCRIM)
        )

        holder.attachEngine(createStockfishEngine())
        attachMoveCoach(isDebug)

        val appSettings = AppSettings(createSettings("chess"))
        // PgnSharer needs the host Activity (for ACTION_SEND), so it's built here, not in the holder.
        val pgnSharer = androidPgnSharer(this)

        setContent {
            AppRoot(
                viewModel = holder.gameViewModel,
                settings = appSettings,
                board3D = androidx.compose.runtime.remember { com.example.myapplication.board3d.androidBoard3DSupport() },
                gameHistory = holder.gameHistory,
                pgnSharer = pgnSharer,
                moveCoachManager = holder.moveCoachManager,
                gameSummaryManager = holder.gameSummaryManager,
            )
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
            holder.moveCoachManager.attachCoachOrchestrator(null)
            holder.gameSummaryManager.attachOrchestrator(null)
            return
        }

        // Initialize Cactus native runtime (required before any CactusLM use)
        initializeCactus(this)

        holder.moveCoachManager.setCoachModelState(
            com.example.myapplication.movecoach.MoveCoachUiState.LoadingModel(
                message = "Downloading Gemma 270M model (first launch only)…"
            )
        )

        CoroutineScope(Dispatchers.IO).launch {
            val executor = VendorRouteExecutor()
            val generator = executor.execute(VendorRoute.CactusLocal())
            // Pre-initialize: download model (first launch) + load into memory
            runCatching { generator?.warmup() }

            holder.moveCoachManager.setCoachModelState(
                com.example.myapplication.movecoach.MoveCoachUiState.LoadingModel(
                    message = "Starting Gemma engine…"
                )
            )

            val contextProvider: suspend () -> com.example.ondeviceai.AiContextSnapshot = {
                com.example.ondeviceai.AiContextSnapshot(
                    isDeviceModelAvailable = true,
                    isAppForegrounded = holder.isForeground,
                    userSetting = com.example.ondeviceai.AiUserSetting.OFFLINE_ONLY,
                )
            }

            holder.moveCoachManager.attachCoachOrchestrator(
                DefaultAiCoachOrchestrator(
                    executor = executor,
                    contextProvider = contextProvider,
                )
            )

            holder.gameSummaryManager.attachOrchestrator(
                DefaultGameSummaryOrchestrator(
                    executor = executor,
                    contextProvider = contextProvider,
                )
            )
            holder.moveCoachManager.hideWindow()
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
    @Volatile var isForeground: Boolean = true
    // Autosave + resume-later (Phase 2): the store is created once for the holder's lifetime
    // (survives config changes) and seeded into the VM. A saved game is loaded here so the VM
    // starts from the restored state; if the saved game was already over it's cleared and a fresh
    // game starts instead (CurrentGameStoreSupport.loadInitialState).
    private val settings = createSettings("chess")
    private val currentGameStore = CurrentGameStore(settings)
    private val restoredState = CurrentGameStoreSupport.loadInitialState(currentGameStore)
    // Seed the VM's runtime show3D + engine difficulty from the persisted settings (first install:
    // 3D on, MEDIUM difficulty).
    private val appSettings = AppSettings(settings)
    val gameViewModel = GameViewModel(
        restoredState.state,
        snapshotSink = currentGameStore.asSnapshotSink(),
        initialShow3D = appSettings.board3DEnabled.value,
        initialEngineDifficulty = appSettings.engineDifficulty.value,
    ).apply {
        aiCoachEnabled = appSettings.aiCoachEnabled.value
    }

    val moveCoachManager = MoveCoachManager(
        gameViewModel = gameViewModel,
        engineDifficultyName = appSettings.engineDifficulty.value.name
    )

    val gameSummaryManager = GameSummaryManager()

    // Phase 3: saved-games history lives on the same Settings backing store, owned by the holder so
    // it survives config changes (and is observed by the History screen across recompositions).
    val gameHistory = GameHistoryRepository(settings)

    init {
        if (restoredState.shouldClear) currentGameStore.clear()
    }

    fun attachEngine(engine: ChessEngine?) {
        gameViewModel.attachEngine(engine)
    }

    override fun onCleared() {
        super.onCleared()
        moveCoachManager.close()
        gameSummaryManager.close()
        gameViewModel.close()
    }
}
