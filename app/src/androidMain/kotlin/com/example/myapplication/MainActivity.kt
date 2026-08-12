package com.example.myapplication

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
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
import com.example.myapplication.monetization.RevenueCatEntitlements
import com.example.myapplication.monetization.UnconfiguredEntitlements
import com.example.myapplication.monetization.revenueCatApiKey
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
        // Skip the attach + warmup only when the retained holder already carries an orchestrator,
        // i.e. a configuration change. Keying this off `savedInstanceState == null` instead looks
        // equivalent but is not: process death also restores a bundle, and there the holder is a
        // brand-new ViewModel with no orchestrator, so the coach would stay dead for the whole
        // session.
        if (!holder.aiAttached) {
            holder.aiAttached = true
            attachGameSummaryModel()
        }

        val appSettings = AppSettings(createSettings("chess"))
        // PgnSharer needs the host Activity (for ACTION_SEND), so it's built here, not in the holder.
        val pgnSharer = androidPgnSharer(this)

        // Injected like pgnSharer/board3D. Null when no key is configured (see
        // generateRevenueCatConfig in app/build.gradle.kts) — AppRoot's UnconfiguredEntitlements
        // default then applies, which is locked.
        val entitlements = RevenueCatEntitlements.createOrNull(
            // isDebug also picks the key: a debug build uses the RevenueCat Test Store key when one
            // is configured, so a dev tap never buys a real Play product.
            apiKey = revenueCatApiKey(debug = isDebug),
            debugLogging = isDebug,
        )
        entitlements?.let { CoroutineScope(Dispatchers.IO).launch { it.refresh() } }

        setContent {
            AppRoot(
                viewModel = holder.gameViewModel,
                settings = appSettings,
                board3D = androidx.compose.runtime.remember { com.example.myapplication.board3d.androidBoard3DSupport() },
                gameHistory = holder.gameHistory,
                pgnSharer = pgnSharer,
                moveCoachManager = holder.moveCoachManager,
                gameSummaryManager = holder.gameSummaryManager,
                entitlements = entitlements
                    // Stays UnconfiguredEntitlements even in debug: the dev unlock is
                    // forceProUnlocked below, which covers all five Pro surfaces *and* leaves
                    // PaywallScreen — which reads LocalEntitlements directly — inspectable.
                    ?: androidx.compose.runtime.remember { UnconfiguredEntitlements() },
                forceProUnlocked = isDebug,
                isDebug = isDebug,
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
     * Attach the on-device model for **Game Summary only**.
     *
     * The Move Coach deliberately gets no orchestrator on Android. Every model available to this
     * platform was benchmarked on hardware and none of them beat the deterministic coach on either
     * latency or truth — see `docs/benchmarks/on-device-ai/android-model-latency-2026-08.md`:
     * 20-36 s for a reasoning model whose deliberation cannot be switched off through Cactus,
     * 5-20 s of generic waffle from `gemma3-1b`, and confidently false statements from `lfm2-700m`
     * that no validator gate can catch. `MoveCoachManager` renders `DeterministicCoach` directly
     * when no orchestrator is attached, instantly and with no download.
     *
     * ML Kit/AICore stays wired but dormant: `probeAvailableLocalVendors` still offers it, and it
     * reports Unavailable on every device tested (a Pixel 10 Pro XL answers
     * `FEATURE_NOT_FOUND: Feature 645`), so it costs nothing and lights up by itself if a device
     * ever provisions the feature.
     */
    private fun attachGameSummaryModel() {
        // Initialize Cactus native runtime (required before any CactusLM use)
        initializeCactus(this)

        CoroutineScope(Dispatchers.IO).launch {
            val executor = VendorRouteExecutor()
            val contextProvider: suspend () -> com.example.ondeviceai.AiContextSnapshot = {
                com.example.ondeviceai.AiContextSnapshot(
                    availableLocalVendors = com.example.ondeviceai.probeAvailableLocalVendors(),
                    isAppForegrounded = holder.isForeground,
                    userSetting = com.example.ondeviceai.AiUserSetting.OFFLINE_ONLY,
                )
            }

            holder.gameSummaryManager.attachOrchestrator(
                DefaultGameSummaryOrchestrator(
                    executor = executor,
                    contextProvider = contextProvider,
                )
            )
            // B18: the orchestrator is attached BEFORE warmup finishes on purpose. Every surface
            // stays live during the ~200 MB first-run download — a coached move routes through the
            // orchestrator, sees AiAvailability.Downloading, and renders the deterministic line
            // (FallbackPresentation.Silent), which is the product rather than an error.
            try {
                val policy = com.example.ondeviceai.AiRoutePolicies.moveCoachOffline
                val context = com.example.ondeviceai.AiContextSnapshot(
                    availableLocalVendors = com.example.ondeviceai.probeAvailableLocalVendors(),
                    isAppForegrounded = true,
                    userSetting = com.example.ondeviceai.AiUserSetting.OFFLINE_ONLY
                )
                // Warm up only the route the decider actually picks; any other decision means
                // there is nothing local to warm.
                val decision = com.example.ondeviceai.AiRoutePolicyDecider.decide(policy, context)
                val generator = (decision as? com.example.ondeviceai.AiRoutePolicyDecider.Decision.RunOnDevice)
                    ?.let { executor.execute(it.route) }
                // B18's determinate progress UI is gone with the coach orchestrator: it wrote into
                // the Move Coach panel, and that panel now answers instantly from
                // DeterministicCoach on every move regardless of the model. Showing a download
                // spinner over a line that is already on screen would be a lie about what the user
                // is waiting for. Game Summary is pull-based and reports its own state when pressed,
                // so the download can simply proceed quietly.
                (generator as? com.example.ondeviceai.cactus.CactusTextGenerator)?.awaitWarmup()
                    ?: generator?.warmup()

                (generator?.status() as? com.example.ondeviceai.AiAvailability.Error)?.let {
                    // Logged, not surfaced: the deterministic coach still answers every move, so
                    // a permanent "unavailable" banner would be noise. Same rule as B17's Silent.
                    Logger.e("MainActivity") { "Cactus failed to load: ${it.message}" }
                }
            } catch (e: Exception) {
                Logger.e("MainActivity") { "Failed to warmup model: ${e.message}" }
            }
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

    /**
     * Whether the on-device model has already been attached for this retained holder.
     *
     * Replaces the old `moveCoachManager.hasOrchestrator` check, which stopped working the moment
     * the coach stopped taking an orchestrator — it would have been false forever, re-running the
     * attach and its warmup on every configuration change. Same reasoning as before: a retained
     * holder means a rotation, a fresh one means process death, and `savedInstanceState` cannot
     * tell those apart.
     */
    @Volatile var aiAttached: Boolean = false
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

    private var backfiller: com.example.myapplication.persistence.GameHistoryBackfiller? = null

    init {
        if (restoredState.shouldClear) currentGameStore.clear()
    }

    fun attachEngine(engine: ChessEngine?) {
        gameViewModel.attachEngine(engine)
        backfiller?.stop()
        if (engine != null) {
            backfiller = com.example.myapplication.persistence.GameHistoryBackfiller(gameHistory, engine)
            backfiller?.start()
        }
    }

    override fun onCleared() {
        super.onCleared()
        moveCoachManager.close()
        gameSummaryManager.close()
        gameViewModel.close()
        backfiller?.stop()
    }
}
