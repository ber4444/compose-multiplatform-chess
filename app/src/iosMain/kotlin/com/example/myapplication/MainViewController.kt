package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.UIKit.UIViewController
import com.example.myapplication.movecoach.MoveCoachUiState
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.DefaultAiCoachOrchestrator
import com.example.ondeviceai.defaultOnDeviceTextGeneratorFactory
import androidx.compose.ui.unit.dp
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.CurrentGameStore
import com.example.myapplication.persistence.CurrentGameStoreSupport
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.asSnapshotSink
import com.example.myapplication.persistence.createSettings
import com.example.myapplication.share.iosPgnSharer

/**
 * iOS entry point. The engine is created and started on the Swift side
 * (StockfishChessEngine) and injected here, mirroring desktop Main.kt.
 * Pass null to play against the built-in CPU.
 *
 * Move Coach flow on iOS mirrors the Android UX: surface a [MoveCoachUiState.LoadingModel]
 * status immediately so the user can distinguish "warming up" / "checking Foundation Models"
 * from a genuinely-unavailable model. The probe runs off-thread; once it returns:
 *   - Available  → attach the orchestrator (next coached move drives Loading→Ready/Fallback)
 *   - Unavailable → surface [MoveCoachUiState.Unavailable] with an actionable hint
 *     (e.g. enable Apple Intelligence in Settings, or upgrade to iOS 26+)
 *
 * Per plan §7 the Swift side registers a Foundation Models provider in iOSApp.swift.init
 * before this runs; the probe here exercises that provider to discover real availability.
 *
 * [filamentFactory] is implemented by the Swift app target and hosts the Metal-native Filament
 * renderer. Keeping it injected mirrors the Stockfish engine bridge while leaving the Kotlin
 * framework independent from Filament's C++ xcframeworks.
 */
@OptIn(ExperimentalForeignApi::class)
fun MainViewController(
    engine: ChessEngine?,
    filamentFactory: com.example.myapplication.board3d.FilamentChessViewFactory,
): UIViewController = ComposeUIViewController {
    // One Settings backing store shared by AppSettings and the autosave store (Phase 2).
    val settings = remember { createSettings("chess") }
    val currentGameStore = remember { CurrentGameStore(settings) }
    val restoredState = remember { CurrentGameStoreSupport.loadInitialState(currentGameStore) }
    DisposableEffect(Unit) {
        if (restoredState.shouldClear) currentGameStore.clear()
        onDispose { }
    }
    val appSettings = remember { AppSettings(settings) }
    val gameHistory = remember { GameHistoryRepository(settings) }
    val pgnSharer = remember { iosPgnSharer() }
    val viewModel = remember {
        GameViewModel(
            restoredState.state,
            snapshotSink = currentGameStore.asSnapshotSink(),
            initialShow3D = appSettings.board3DEnabled.value,
            initialEngineDifficulty = appSettings.engineDifficulty.value,
        )
    }
    val moveCoachManager = remember {
        com.example.myapplication.movecoach.MoveCoachManager(
            gameViewModel = viewModel,
            engineDifficultyName = appSettings.engineDifficulty.value.name
        )
    }
    DisposableEffect(Unit) {
        viewModel.attachEngine(engine)

        // Surface loading state IMMEDIATELY so the panel mounts with a clear
        // "checking availability" message instead of staying Hidden through
        // the probe window (which on first launch can take seconds).
        moveCoachManager.setCoachModelState(
            MoveCoachUiState.LoadingModel(message = "Checking Foundation Models availability…")
        )

        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            val availability = probeFoundationModelsAvailability()
            when (availability) {
                is AiAvailability.Available -> {
                    // Attach the orchestrator; resets state to Hidden. Next coached
                    // move drives Loading(move) → Ready/Fallback/Error.
                    //
                    // CRITICAL: pass a contextProvider that reports
                    // isDeviceModelAvailable=true — the default context provider
                    // returns false, which makes AiRoutePolicyDecider short-circuit
                    // to FALLBACK_NO_LOCAL_MODEL without ever calling the generator.
                    // We only reach this branch after the probe confirmed the
                    // Foundation Models generator reports Available, so reporting
                    // true here is consistent with reality.
                    moveCoachManager.attachCoachOrchestrator(
                        DefaultAiCoachOrchestrator(
                            factory = defaultOnDeviceTextGeneratorFactory(),
                            contextProvider = {
                                com.example.ondeviceai.AiContextSnapshot(
                                    isDeviceModelAvailable = true,
                                    isAppForegrounded = true,
                                    userSetting = com.example.ondeviceai.AiUserSetting.OFFLINE_ONLY,
                                )
                            },
                        )
                    )
                }
                else -> {
                    val reason = availabilityToHint(availability)
                    Logger.w("MainViewController") { "Foundation Models unavailable: $reason" }
                    moveCoachManager.setCoachModelState(MoveCoachUiState.Unavailable(reason = reason))
                }
            }
        }

        // Testability hook for the simulator screenshot harness (tools/ios_3d_screenshot.sh): start
        // directly on the 3D board so it can be captured without a human tapping the toggle.
        if (platform.posix.getenv("CHESS_START_3D") != null) viewModel.setShow3D(true)
        onDispose {
            scope.cancel()
            moveCoachManager.close()
            viewModel.close() // also closes the attached engine and cancels coach job
        }
    }
    MyApplicationTheme {
        AppRoot(
            viewModel = viewModel,
            settings = appSettings,
            board3D = remember { com.example.myapplication.board3d.iosBoard3DSupport(filamentFactory) },
            gameHistory = gameHistory,
            pgnSharer = pgnSharer,
            moveCoachManager = moveCoachManager,
            switchTopPadding = (-16).dp
        )
    }
}

/**
 * Probe Apple Foundation Models availability via the registered Kotlin bridge.
 * Returns the [AiAvailability] reported by the Swift `FoundationMoveCoachBridge`,
 * or null if no provider was registered (Swift side didn't initialize).
 */
private suspend fun probeFoundationModelsAvailability(): AiAvailability {
    val factory = defaultOnDeviceTextGeneratorFactory()
    val generator = runCatching { factory.create() }.getOrElse {
        return AiAvailability.Error("generator factory failed: ${it.message}")
    } ?: return AiAvailability.Unavailable
    return runCatching { generator.status() }.getOrElse {
        AiAvailability.Error("status probe failed: ${it.message}")
    }
}

/**
 * Map the Foundation Models availability to a user-actionable hint. The goal is
 * to tell the user the next step, not just report a generic "unavailable".
 * The Foundation Models API itself returns the specific reason (e.g. iOS too old,
 * region not supported, Apple Intelligence not enabled) — we just translate it
 * into a concrete next step.
 */
private fun availabilityToHint(availability: AiAvailability): String {
    val detail = when (availability) {
        is AiAvailability.Error -> availability.message
        is AiAvailability.Busy -> "Foundation Models is busy"
        is AiAvailability.Downloadable -> "model downloadable but not installed"
        is AiAvailability.Downloading -> "model downloading"
        is AiAvailability.Unavailable -> "unavailable"
        is AiAvailability.Available -> return "Available" // unreachable
    }
    return "Apple Intelligence isn't available ($detail). " +
        "Open Settings → Apple Intelligence & Siri to enable it on supported " +
        "iOS 26+ devices, then relaunch the app."
}
