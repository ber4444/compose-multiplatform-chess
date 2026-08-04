package com.example.myapplication

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import androidx.compose.ui.ExperimentalComposeUiApi
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.CurrentGameStore
import com.example.myapplication.persistence.CurrentGameStoreSupport
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.asSnapshotSink
import com.example.myapplication.persistence.createSettings
import com.example.myapplication.share.wasmPgnSharer
import com.example.myapplication.movecoach.GameSummaryManager
import com.example.myapplication.movecoach.MoveCoachManager
import com.example.myapplication.movecoach.MoveCoachUiState
import com.example.ondeviceai.AiContextSnapshot
import com.example.ondeviceai.AiUserSetting
import com.example.ondeviceai.DefaultAiCoachOrchestrator
import com.example.ondeviceai.DefaultGameSummaryOrchestrator
import com.example.ondeviceai.VendorRouteExecutor
import com.example.ondeviceai.AiRoute
import co.touchlab.kermit.Logger

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.title = "Chess"
    ComposeViewport("ComposeTarget") {
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
        val pgnSharer = remember { wasmPgnSharer() }
        val viewModel = remember {
            GameViewModel(
                restoredState.state,
                snapshotSink = currentGameStore.asSnapshotSink(),
                initialShow3D = appSettings.board3DEnabled.value,
                initialEngineDifficulty = appSettings.engineDifficulty.value,
            )
        }
        var backfiller: com.example.myapplication.persistence.GameHistoryBackfiller? = null
        LaunchedEffect(Unit) {
            val engine = WasmStockfishEngine()
            if (engine.start()) {
                viewModel.attachEngine(engine)   // viewModel now owns engine.close()
                backfiller = com.example.myapplication.persistence.GameHistoryBackfiller(gameHistory, engine)
                backfiller?.start()
            } else {
                Logger.w("Main") { "Stockfish wasm worker failed to start; using CPU fallback" }
                engine.close()
            }
        }

        // On-device Move Coach (LiteRT-LM for Web). Gated behind ?coach=1 on the URL
        // so the default page load isn't forced into a ~347 MB model download — mirrors
        // Android's FLAG_DEBUGGABLE gate and desktop's CHESS_ENABLE_COACH env var.
        // Requires WebGPU (Chrome/Edge); on Firefox/Safari the generator's status()
        // returns Unavailable and the orchestrator falls back to MoveCoachFallback.
        val moveCoachManager = remember {
            MoveCoachManager(viewModel, appSettings.engineDifficulty.value.name)
        }
        val gameSummaryManager = remember { GameSummaryManager() }
        LaunchedEffect(Unit) {
            if (!isCoachEnabled()) return@LaunchedEffect
            attachMoveCoach(moveCoachManager, gameSummaryManager)
        }
        DisposableEffect(Unit) {
            onDispose {
                backfiller?.stop()
                moveCoachManager.close()
                gameSummaryManager.close()
                viewModel.close()
            }
        }

        AppRoot(
            viewModel = viewModel,
            settings = appSettings,
            board3D = com.example.myapplication.board3d.wasmBoard3DSupport(viewModel),
            gameHistory = gameHistory,
            pgnSharer = pgnSharer,
            moveCoachManager = moveCoachManager,
            gameSummaryManager = gameSummaryManager,
            // Locked by default, like desktop, so the paywall renders in a browser window too. No
            // store on wasm, so the unlock is local and free, persisted through StorageSettings.
            entitlements = androidx.compose.runtime.remember {
                com.example.myapplication.monetization.NoOpEntitlements(
                    initialUnlocked = appSettings.proUnlocked,
                    onUnlockChanged = appSettings::setProUnlocked,
                )
            },
        )
    }
}

/** `?coach=1` on the page URL opts in to the on-device coach. */
private fun isCoachEnabled(): Boolean =
    js("(new URLSearchParams(self.location.search)).get('coach') === '1'")

/**
 * Attach the on-device Move Coach + Game Summary orchestrators backed by LiteRT-LM
 * for Web. Mirrors `MainActivity.attachMoveCoach` (Android) and the desktop
 * `attachMoveCoach`: builds the default factory, warms it up (which probes WebGPU
 * and, if available, lazily loads the model + CDN module inside the worker), then
 * attaches the orchestrators over the shared factory.
 *
 * On browsers without WebGPU the warmup surfaces an `AiAvailability.Unavailable`
 * status and the orchestrators route every request to the deterministic
 * `MoveCoachFallback` — the panel still renders, just with the rule-based text.
 */
private suspend fun attachMoveCoach(
    moveCoachManager: MoveCoachManager,
    gameSummaryManager: GameSummaryManager,
) {
    moveCoachManager.setCoachModelState(
        MoveCoachUiState.LoadingModel(
            message = "Loading LiteRT-LM (WebGPU required, model ~2 GB)…"
        )
    )

    val executor = VendorRouteExecutor()
    val policy = com.example.ondeviceai.AiRoutePolicies.moveCoachOffline
    val context = com.example.ondeviceai.AiContextSnapshot(
        availableLocalVendors = com.example.ondeviceai.probeAvailableLocalVendors(),
        isAppForegrounded = true,
        userSetting = com.example.ondeviceai.AiUserSetting.OFFLINE_ONLY
    )
    val decision = com.example.ondeviceai.AiRoutePolicyDecider.decide(policy, context)
    val generator = (decision as? com.example.ondeviceai.AiRoutePolicyDecider.Decision.RunOnDevice)
        ?.let { executor.execute(it.route) }
    runCatching { generator?.warmup() }
        .onFailure { Logger.w("Main") { "LiteRT-LM warmup failed: ${it.message}" } }

    moveCoachManager.setCoachModelState(
        MoveCoachUiState.LoadingModel(message = "Starting LiteRT-LM engine…")
    )

    val contextProvider: suspend () -> AiContextSnapshot = {
        AiContextSnapshot(
            availableLocalVendors = com.example.ondeviceai.probeAvailableLocalVendors(),
            userSetting = AiUserSetting.OFFLINE_ONLY,
        )
    }

    moveCoachManager.attachCoachOrchestrator(
        DefaultAiCoachOrchestrator(
            executor = executor,
            contextProvider = contextProvider,
        )
    )
    gameSummaryManager.attachOrchestrator(
        DefaultGameSummaryOrchestrator(
            executor = executor,
            contextProvider = contextProvider,
        )
    )
    moveCoachManager.hideWindow()
}
