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
 * Failures (no native lib on Intel Mac, download error, model corruption) are
 * captured inside the generator's `ensureInitialized` and surface as an
 * `AiAvailability.Error`, after which the orchestrator falls back to the
 * deterministic `MoveCoachFallback` — the panel still renders, just with the
 * rule-based explanation instead of the model's.
 */
private fun attachMoveCoach(
    moveCoachManager: MoveCoachManager,
    gameSummaryManager: GameSummaryManager,
) {
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

        moveCoachManager.setCoachModelState(
            MoveCoachUiState.LoadingModel(message = "Starting LiteRT-LM engine…")
        )

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
