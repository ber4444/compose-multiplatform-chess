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
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.DefaultAiCoachOrchestrator
import com.example.ondeviceai.DefaultGameSummaryOrchestrator
import com.example.ondeviceai.VendorRouteExecutor
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
 * **The Move Coach takes no orchestrator here, and the probe no longer speaks for it.** iOS used to
 * be the one platform that attached an on-device model to the coach; measuring Foundation Models
 * against the same 100 golden positions as ML Kit, on identical prompts, ended that (2026-08-15 —
 * see `docs/benchmarks/on-device-ai/android-model-latency-2026-08.md`). It is much the faster and
 * more fluent writer and the less truthful one, and the coach's job is to be true. So the coach is
 * `DeterministicCoach` on every platform, which also means it is no longer a Pro surface — the
 * paywall's feature list is keyed off `MoveCoachManager.hasOrchestrator` and drops that line by
 * itself.
 *
 * The probe still runs, for Game Summary and Rules Q&A. Per plan §7 the Swift side registers a
 * Foundation Models provider in iOSApp.swift.init before this runs; the probe here exercises that
 * provider to discover real availability.
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
    val gameSummaryManager = remember {
        com.example.myapplication.movecoach.GameSummaryManager()
    }
    // Platform.isDebugBinary is the K/N equivalent of Android's FLAG_DEBUGGABLE: it selects the
    // RevenueCat Test Store key (when configured) and the SDK's debug logging together, so a
    // debug simulator/device build never runs a purchase against a real App Store product. It is
    // also what AppRoot's `isDebug` / `forceProUnlocked` read, so it is hoisted here rather than
    // scoped to the entitlements lambda — they must all agree on what "debug" means.
    // The opt-in is scoped to this expression rather than the file or the module: the API is
    // experimental, and a module-wide opt-in would silently cover future uses that nobody
    // reviewed. There is no stable equivalent — Kotlin/Native exposes no other build-type probe.
    val debug = remember {
        @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
        kotlin.native.Platform.isDebugBinary
    }
    // Injected like pgnSharer. Null when no key is configured (see generateRevenueCatConfig in
    // app/build.gradle.kts); the locked UnconfiguredEntitlements then applies.
    val entitlements = remember {
        com.example.myapplication.monetization.RevenueCatEntitlements.createOrNull(
            apiKey = com.example.myapplication.monetization.revenueCatApiKey(debug = debug),
            debugLogging = debug,
        )
    }
    DisposableEffect(Unit) {
        viewModel.attachEngine(engine)

        // The coach no longer waits on the probe, because it no longer depends on the answer: it
        // renders `DeterministicCoach` either way (see the Available branch below). Leaving the old
        // "Checking Foundation Models availability…" state here would strand the panel in it, since
        // nothing attaches an orchestrator afterwards to clear it. The manager's default is Hidden,
        // and the first coached move drives it from there.
        //
        // The probe still runs — Game Summary and Rules Q&A depend on it.

        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch { entitlements?.refresh() }
        scope.launch {
            val availability = probeFoundationModelsAvailability()
            when (availability) {
                is AiAvailability.Available -> {
                    // Attaches Game Summary only — the coach's orchestrator is deliberately absent,
                    // see below.
                    //
                    // CRITICAL: pass a contextProvider that reports
                    // isDeviceModelAvailable=true — the default context provider
                    // returns false, which makes AiRoutePolicyDecider short-circuit
                    // to FALLBACK_NO_LOCAL_MODEL without ever calling the generator.
                    // We only reach this branch after the probe confirmed the
                    // Foundation Models generator reports Available, so reporting
                    // true here is consistent with reality.
                    val contextProvider: suspend () -> com.example.ondeviceai.AiContextSnapshot = {
                        com.example.ondeviceai.AiContextSnapshot(
                            availableLocalVendors = com.example.ondeviceai.probeAvailableLocalVendors(),
                            isAppForegrounded = true,
                            userSetting = com.example.ondeviceai.AiUserSetting.OFFLINE_ONLY,
                        )
                    }
                    val executor = VendorRouteExecutor()
                    // The Move Coach deliberately does **not** get an orchestrator, on this
                    // platform or any other. Foundation Models was measured against the same 100
                    // golden positions as ML Kit, on identical prompts, on 2026-08-15: 650 ms
                    // median against 4.4 s and half the fluency violations, but 75/100 grounded
                    // against 91, an LLM judge preferring the deterministic line 54-46, and a hand
                    // read finding 5 of 8 sampled flags real — it calls an inaccuracy a mistake,
                    // calls a best move bad, and offers the player's own hanging pawn as the reason
                    // the move was good. Where it does not contradict the facts it usually repeats
                    // the deterministic sentence verbatim. Faster and more fluent is not better
                    // when the surface's job is to be true.
                    //
                    // `MoveCoachManager` renders `DeterministicCoach` with no orchestrator
                    // attached, so the panel still answers instantly — see
                    // docs/benchmarks/on-device-ai/android-model-latency-2026-08.md.
                    gameSummaryManager.attachOrchestrator(
                        DefaultGameSummaryOrchestrator(
                            executor = executor,
                            contextProvider = contextProvider,
                        )
                    )
                }
                else -> {
                    val reason = availabilityToHint(availability)
                    Logger.w("MainViewController") { "Foundation Models unavailable: $reason" }
                    // Nothing to tell the coach panel: with no model on either branch it shows the
                    // deterministic line, and an "unavailable" banner would report the absence of
                    // something the user was never going to get.
                    gameSummaryManager.attachOrchestrator(null)
                }
            }
        }

        // Testability hook for the simulator screenshot harness (tools/ios_3d_screenshot.sh): start
        // directly on the 3D board so it can be captured without a human tapping the toggle.
        if (platform.posix.getenv("CHESS_START_3D") != null) viewModel.setShow3D(true)
        
        var backfiller: com.example.myapplication.persistence.GameHistoryBackfiller? = null
        if (engine != null) {
            backfiller = com.example.myapplication.persistence.GameHistoryBackfiller(gameHistory, engine)
            backfiller.start()
        }
        
        onDispose {
            scope.cancel()
            backfiller?.stop()
            moveCoachManager.close()
            gameSummaryManager.close()
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
            gameSummaryManager = gameSummaryManager,
            entitlements = entitlements
                // Stays UnconfiguredEntitlements even in debug: the dev unlock is forceProUnlocked
                // below, which covers all five Pro surfaces *and* leaves PaywallScreen — which reads
                // LocalEntitlements directly — inspectable.
                ?: remember { com.example.myapplication.monetization.UnconfiguredEntitlements() },
            switchTopPadding = (-16).dp,
            forceProUnlocked = debug,
            isDebug = debug,
        )
    }
}

/**
 * Probe Apple Foundation Models availability via the registered Kotlin bridge.
 * Returns the [AiAvailability] reported by the Swift `FoundationMoveCoachBridge`,
 * or null if no provider was registered (Swift side didn't initialize).
 */
private suspend fun probeFoundationModelsAvailability(): AiAvailability {
    val executor = VendorRouteExecutor()
    val policy = com.example.ondeviceai.AiRoutePolicies.moveCoachOffline
    val context = com.example.ondeviceai.AiContextSnapshot(
        availableLocalVendors = com.example.ondeviceai.probeAvailableLocalVendors(),
        isAppForegrounded = true,
        userSetting = com.example.ondeviceai.AiUserSetting.OFFLINE_ONLY
    )
    val decision = com.example.ondeviceai.AiRoutePolicyDecider.decide(policy, context)
    val route = (decision as? com.example.ondeviceai.AiRoutePolicyDecider.Decision.RunOnDevice)
        ?.route ?: return AiAvailability.Unavailable
    val generator = runCatching { executor.execute(route) }.getOrElse {
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
