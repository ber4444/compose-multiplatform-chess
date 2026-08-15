package com.example.myapplication

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
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

        if (isDebug && intent.hasExtra("bench_summary_iterations")) {
            val iterations = intent.getIntExtra("bench_summary_iterations", 1)
            keepBenchInForeground()
            CoroutineScope(Dispatchers.IO).launch {
                com.example.myapplication.bench.runAndroidSummaryBench(this@MainActivity, iterations)
                finish()
            }
            return
        }

        if (isDebug && intent.hasExtra("bench_iterations")) {
            val iterations = intent.getIntExtra("bench_iterations", 1)
            keepBenchInForeground()
            CoroutineScope(Dispatchers.IO).launch {
                runAndroidBench(this@MainActivity, iterations)
                // Mirror the JSONL into the app's external files dir. `filesDir` needs `run-as`,
                // which the remote-device tooling could not do — that is why an earlier run was read
                // out of a deliberate crash. This path is plain `adb pull`.
                runCatching {
                    val source = java.io.File(filesDir, "bench/results.jsonl")
                    val target = java.io.File(getExternalFilesDir(null), "bench/results.jsonl")
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                    android.util.Log.i("AndroidBenchRunner", "bench results at ${target.absolutePath}")
                }.onFailure { android.util.Log.w("AndroidBenchRunner", "could not mirror results", it) }
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
        attachOnDeviceAi()

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

    /**
     * Wires ML Kit / AICore into the two on-device surfaces, or leaves them deterministic.
     *
     * **This is the integration the Android seam was missing.** `probeAvailableLocalVendors()` had
     * no caller outside `bench/`, so the app's AICore path could only be exercised by running the
     * benchmark — an integration nothing in the product ever executed is one that quietly rots. It
     * now lives on the launch path, one constant from live, and the constant is the record of a
     * decision rather than of an omission.
     *
     * Three details are load-bearing and none of them are obvious:
     *
     *  - **The probe can download.** On a device reporting `DOWNLOADABLE`/`DOWNLOADING`,
     *    `probeAvailableLocalVendors()` calls `warmup()`, which awaits an AICore feature fetch. So
     *    it is called *inside* the flag, not before it: "inactive" has to mean no network and no
     *    provisioning, not merely no visible model.
     *  - **`isAppForegrounded` is read per request, not captured.** AICore refuses to generate in the
     *    background — `[ErrorCode 30] Background usage is blocked` — so a backgrounded request must
     *    reach `AiRoutePolicyDecider` as *not foregrounded* and fall back cleanly, rather than
     *    reaching the model and returning an error the orchestrator can only report as a generation
     *    failure. `holder.isForeground` is maintained by `onStart`/`onStop`.
     *  - **The vendor list is probed once and reused.** Re-probing per request costs an AICore
     *    `checkStatus()` round trip on every coached move; thermal and quota conditions, which do
     *    change per request, are the decider's own inputs and are unaffected.
     *
     * Both flags are off today, for different reasons — see the constants and
     * `docs/benchmarks/on-device-ai/game-summary-2026-08.md`.
     */
    private fun attachOnDeviceAi() {
        // Unconditional, and *before* the probe. Not "no model, no summary": the composed turning
        // points are a complete answer, and doing this first means the button is live from the first
        // frame instead of after a probe that may await an AICore feature download. When a model is
        // attached below it takes over; `deterministicEnabled` only applies while the orchestrator
        // is null, so the two cannot fight.
        holder.gameSummaryManager.enableDeterministic()

        if (!ATTACH_GAME_SUMMARY && !ATTACH_MOVE_COACH) return

        CoroutineScope(Dispatchers.IO).launch {
            val vendors = com.example.ondeviceai.probeAvailableLocalVendors()
            Logger.i("MainActivity") { "on-device vendors: $vendors" }
            // Every emulator, and every device without an AICore feature for this model. The
            // deterministic summary above already stands, and the coach renders DeterministicCoach
            // with no orchestrator, so there is nothing further to do.
            if (vendors.isEmpty()) return@launch

            val executor = com.example.ondeviceai.VendorRouteExecutor()
            val contextProvider: suspend () -> com.example.ondeviceai.AiContextSnapshot = {
                com.example.ondeviceai.AiContextSnapshot(
                    availableLocalVendors = vendors,
                    isAppForegrounded = holder.isForeground,
                    userSetting = com.example.ondeviceai.AiUserSetting.OFFLINE_ONLY,
                )
            }

            if (ATTACH_GAME_SUMMARY) {
                holder.gameSummaryManager.attachOrchestrator(
                    com.example.ondeviceai.DefaultGameSummaryOrchestrator(
                        executor = executor,
                        contextProvider = contextProvider,
                    ),
                )
            }
            if (ATTACH_MOVE_COACH) {
                holder.moveCoachManager.attachCoachOrchestrator(
                    com.example.ondeviceai.DefaultAiCoachOrchestrator(
                        executor = executor,
                        contextProvider = contextProvider,
                    ),
                )
            }
        }
    }

    /**
     * Turns the screen on, shows over the keyguard, and holds both for the run.
     *
     * **AICore refuses to generate unless the calling app is in the foreground** — it fails the
     * request with `[ErrorCode 30] Background usage is blocked`, per row, in under a second, and the
     * orchestrator can only report it as an ordinary generation error. A `adb shell am start` on a
     * locked or sleeping device therefore produces a full JSONL of plausible-looking fallbacks that
     * measure nothing: it cost 3 rows of the first Game Summary run and all 12 of the second, and
     * `svc power stayon usb` does not prevent it because the keyguard, not the screen timeout, is
     * what stops the activity being resumed.
     *
     * Bench-only, so this never touches a shipped launch: both callers sit behind `isDebug` plus a
     * bench intent extra.
     */
    private fun keepBenchInForeground() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // Only dismisses a swipe keyguard; a secured device still needs unlocking by hand, which the
        // run will make obvious rather than silently mis-measure.
        (getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)
            ?.requestDismissKeyguard(this, null)
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

        /**
         * Whether ML Kit / AICore writes the Move Coach panel. **Off, and decided.**
         *
         * `nano-v3` passes `MoveCoachResponseValidator` on 95/100 golden positions against ~89 for
         * `DeterministicCoach`, but a hand read finds invention the validator cannot catch — a motif
         * belonging to the played move reattached to the engine's preferred move, an invented
         * "opens up the h-file" — and it takes ~4.4 s to say what the deterministic line says
         * instantly. The panel is latency-bound and the deterministic text is already true, so
         * faster-and-more-fluent is not a reason to attach it.
         *
         * Reopening this means covering motif attribution and file/diagonal claims in the validator
         * first; that rule is the gate. See
         * `docs/benchmarks/on-device-ai/android-model-latency-2026-08.md`.
         */
        private const val ATTACH_MOVE_COACH = false

        /**
         * Whether ML Kit / AICore writes the Game Summary. **Off, and open** — a different question
         * from [ATTACH_MOVE_COACH], which is why these are two constants and not one.
         *
         * With the prompt's raw PGN removed and `noRepeatNgramSize` widened, AICore went from 7/12
         * to **12/12** on the 2026-08 fixtures at ~12 s, citing exactly the code-chosen turning
         * points in 11 of 12 and inventing nothing — the best on-device output measured on this
         * project. What holds it back is not quality: this surface has **no response validator at
         * all**, so residual decoration ("a blunder that weakened your pawn structure", about a
         * knight move) reaches the user unchallenged, and 12 games is a small sample for a surface
         * where one bad summary is the entire answer.
         *
         * Flip it after the validator lands and a larger fixture set agrees — see
         * `docs/plans/on-device-ai-next-steps.md`. The floor does not move when you do: a rejected
         * summary falls back to `GameSummaryGrounding`, which is exactly what ships today.
         */
        private const val ATTACH_GAME_SUMMARY = false
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
