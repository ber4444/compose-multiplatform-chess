package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.unit.dp
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.CurrentGameStore
import com.example.myapplication.persistence.CurrentGameStoreSupport
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.asSnapshotSink
import com.example.myapplication.persistence.createSettings
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.myapplication.board3d.desktopBoard3DSupport
import com.example.myapplication.share.desktopPgnSharer
import com.example.myapplication.movecoach.GameSummaryManager
import com.example.myapplication.movecoach.MoveCoachManager
import com.example.myapplication.movecoach.MoveCoachUiState
import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.AiContextSnapshot
import com.example.ondeviceai.AiUserSetting
import com.example.ondeviceai.DefaultAiCoachOrchestrator
import com.example.ondeviceai.DefaultGameSummaryOrchestrator
import com.example.ondeviceai.defaultOnDeviceTextGeneratorFactory

fun main() = application {
    // One Settings backing store shared by AppSettings and the autosave store (Phase 2). `remember`
    // so a recomposition doesn't re-read disk mid-session.
    val settings = remember { createSettings("chess") }
    // Autosave + resume-later: load any saved game before constructing the VM so it seeds from it.
    val currentGameStore = remember { CurrentGameStore(settings) }
    val restoredState = remember { CurrentGameStoreSupport.loadInitialState(currentGameStore) }
    DisposableEffect(Unit) {
        if (restoredState.shouldClear) currentGameStore.clear()
        onDispose { }
    }
    val appSettings = remember { AppSettings(settings) }
    val gameHistory = remember { GameHistoryRepository(settings) }
    val pgnSharer = remember { desktopPgnSharer() }
    val viewModel = remember {
        // Seed the VM's runtime show3D + engine difficulty from the persisted settings.
        GameViewModel(
            restoredState.state,
            snapshotSink = currentGameStore.asSnapshotSink(),
            initialShow3D = appSettings.board3DEnabled.value,
            initialEngineDifficulty = appSettings.engineDifficulty.value,
        )
    }
    val board3D = remember { desktopBoard3DSupport() }

    // On-device Move Coach (LiteRT-LM). Gated behind CHESS_ENABLE_COACH=1 so the default
    // `./gradlew :app:run` workflow isn't forced into a ~347 MB model download — mirrors
    // Android's FLAG_DEBUGGABLE gate. When off the managers stay attached to a null
    // orchestrator and the coach panel stays Hidden (no behavior change vs. before).
    val moveCoachManager = remember {
        MoveCoachManager(viewModel, appSettings.engineDifficulty.value.name)
    }
    val gameSummaryManager = remember { GameSummaryManager() }

    DisposableEffect(Unit) {
        val engine = DesktopStockfishEngine()
        CoroutineScope(Dispatchers.IO).launch {
            if (engine.start()) {
                viewModel.attachEngine(engine)
            } else {
                Logger.w("Main") { "Failed to start stockfish." }
            }
        }
        if (System.getenv("CHESS_ENABLE_COACH") == "1") {
            attachMoveCoach(moveCoachManager, gameSummaryManager)
        }
        onDispose {
            engine.close()
            moveCoachManager.close()
            gameSummaryManager.close()
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
            board3D = board3D,
            gameHistory = gameHistory,
            pgnSharer = pgnSharer,
            moveCoachManager = moveCoachManager,
            gameSummaryManager = gameSummaryManager,
        )
    }
}

/**
 * Attach the on-device Move Coach + Game Summary orchestrators backed by LiteRT-LM
 * (`litertlm-jvm`). Mirrors `MainActivity.attachMoveCoach` on Android: builds the
 * default factory, warms it up (which downloads + loads the model on first launch),
 * then attaches [DefaultAiCoachOrchestrator] / [DefaultGameSummaryOrchestrator] over
 * the same shared factory. The orchestrators are reused unchanged — only the
 * platform generator differs (see `:onDeviceAi` desktopMain).
 *
 * Failures (no native lib on Intel Mac, download error, model corruption, or the
 * litertlm-jvm 0.14.0 coroutines-bridge mismatch) are captured inside the
 * generator's `ensureInitialized` and surfaced via `status()`. Without the
 * `status()` check below, an init failure left the panel pinned on
 * `LoadingModel("Starting LiteRT-LM engine…")` forever — the orchestrators still
 * attached and would fall back to [com.example.ondeviceai.MoveCoachFallback] on the
 * first coached move, but the user saw an infinite spinner until then.
 *
 * Set `CHESS_COACH_DEBUG=1` to keep the panel showing the outcome (Ready/Error)
 * after load instead of hiding it; by default a successful load collapses the
 * panel back to Hidden so it doesn't sit empty until the first coached move.
 */
private fun attachMoveCoach(
    moveCoachManager: MoveCoachManager,
    gameSummaryManager: GameSummaryManager,
) {
    val debug = System.getenv("CHESS_COACH_DEBUG") == "1"

    moveCoachManager.setCoachModelState(
        MoveCoachUiState.LoadingModel(
            message = "Downloading Qwen3 0.6B model (first launch only, ~347 MB)…"
        )
    )

    CoroutineScope(Dispatchers.IO).launch {
        val factory = defaultOnDeviceTextGeneratorFactory()
        val generator = factory.create()
        runCatching { generator?.warmup() }
            .onFailure { Logger.w("Main") { "LiteRT-LM warmup failed: ${it.message}" } }

        // Check the real status after warmup so an init failure surfaces in the panel
        // (and the log, via LitertLmTextGenerator.ensureInitialized) instead of leaving
        // an infinite spinner. status() runs ensureInitialized() again — cheap if it
        // already succeeded, and the only path that surfaces initializationFailed if it
        // didn't.
        val status = generator?.status()
        Logger.i("Main") { "LiteRT-LM status after warmup: $status" }
        when (status) {
            is AiAvailability.Error -> {
                moveCoachManager.setCoachModelState(
                    MoveCoachUiState.Error("LiteRT-LM failed to load: ${status.message}")
                )
                return@launch
            }
            AiAvailability.Unavailable, AiAvailability.Busy, null -> {
                moveCoachManager.setCoachModelState(MoveCoachUiState.Unavailable())
                return@launch
            }
            AiAvailability.Available, is AiAvailability.Downloadable, AiAvailability.Downloading -> {
                if (debug) {
                    moveCoachManager.setCoachModelState(
                        MoveCoachUiState.LoadingModel(message = "LiteRT-LM ready.")
                    )
                } else {
                    moveCoachManager.hideWindow()
                }
            }
        }

        val contextProvider: suspend () -> AiContextSnapshot = {
            AiContextSnapshot(
                isDeviceModelAvailable = true,
                userSetting = AiUserSetting.OFFLINE_ONLY,
            )
        }

        moveCoachManager.attachCoachOrchestrator(
            DefaultAiCoachOrchestrator(
                factory = factory,
                contextProvider = contextProvider,
            )
        )
        gameSummaryManager.attachOrchestrator(
            DefaultGameSummaryOrchestrator(
                factory = factory,
                contextProvider = contextProvider,
            )
        )
    }
}
