# Stockfish for Wasm — Implementation Plan (GitHub issue #30)

> Self-contained plan for an AI coding agent. Repo: `compose-multiplatform-chess`; branch off `main` (suggested name: `stockfish-wasm`). All paths are relative to the repo root. Line numbers are verified anchors on `main` at the time of writing — treat them as anchors, not exact offsets.

## Context

[Issue #30](https://github.com/ber4444/compose-multiplatform-chess/issues/30): the app "works with linux and android only, and wasm is using a very basic engine instead which should be removed in favor of stockfish." Concretely: `app/src/wasmJsMain/kotlin/com/example/myapplication/Main.kt` creates `GameViewModel()` and never calls `attachEngine`, so on the web target Black always plays via the simple `pickMoveCPU` fallback and the UI permanently shows "Stockfish: off". Android and desktop run real Stockfish through `BaseStockfishEngine` (jvmCommonMain — blocking UCI over a spawned process).

This plan brings Stockfish to the browser by vendoring the official **single-threaded "lite" Stockfish 18 WebAssembly build** and driving it as a **Web Worker** speaking UCI over `postMessage`. The `GameViewModel` stays platform-agnostic: the new engine implements the existing `ChessEngine` interface, and the whole `pickMoveStockfish` → `pickMoveCPU` fallback chain is preserved (so `pickMoveCPU` is *not* deleted — it remains the same safety net the JVM targets already rely on; the wasm target simply stops using it as the primary engine).

Two structural consequences drive most of the diff:

1. **`ChessEngine` becomes a suspend interface.** A Web Worker replies asynchronously and the browser event loop cannot block, while today's `getBestMove`/`evaluate` are synchronous (the JVM impl blocks on a `LinkedBlockingQueue`). `getBestMove` and `evaluate` become `suspend fun`, which ripples through `pickMoveStockfish` (Move.kt), `evaluatePositionCp` (DrawAgreement.kt), `GameViewModel` (`moveCPU`'s `pickMove` lambda, `animationEnd`, `offerDraw`, `tryBlackDrawOffer`, `moveBlackWithEngine`) and every test fake. The JVM engine keeps its working blocking internals, wrapped in `withContext(Dispatchers.IO)` — do **not** restructure its threads/queues.
2. **The UCI protocol state machine for the new engine lives in commonMain** (`UciProtocolClient` over a tiny `UciTransport` interface) so it is unit-testable with a fake transport on every target — including `:app:wasmJsBrowserTest` (Karma/headless Chrome), which is already part of `:app:check`, so the exact protocol code that ships to the browser is exercised *in* the browser in CI. Only the `Worker`-backed transport (wasmJsMain) is an untested thin shim. `BaseStockfishEngine` is deliberately **not** migrated onto the new client — working code, keep the blast radius at signature changes.

### Rejected alternatives (for the record)

- **Remote UCI proxy service** (suggested in the issue's hints) — there is no backend; it breaks offline use and CI.
- **Multithreaded / full-NNUE wasm builds** — multithreaded needs `SharedArrayBuffer`, which requires COOP/COEP cross-origin-isolation headers; impossible on plain static hosts (e.g. GitHub Pages). The full-strength single-threaded wasm is **113 MB** — over GitHub's 100 MB file limit (the same limit that pinned Android to sf_17, see `docs/Stockfish.md`). The lite single-threaded build is ~7 MB, needs no special headers, and is still far stronger than any human opponent.
- **Kotlin `npm("stockfish", ...)` dependency** — the worker `.js` + `.wasm` would sit in `node_modules`, and webpack must be coerced via `webpack.config.d` copy-plugin rules to emit them into the bundle output; brittle across Kotlin/JS toolchain upgrades. Vendoring matches existing repo practice (`libstockfish.so` in jniLibs).

## Key existing code (verified)

| What | Where |
|---|---|
| `ChessEngine`: `fun getBestMove(fen): String?`, `fun close()`, `fun evaluate(fen): Int? = null` | `app/src/commonMain/kotlin/com/example/myapplication/ChessEngine.kt` |
| `BaseStockfishEngine` — blocking UCI over `Process` + reader thread + `LinkedBlockingQueue`; consts `DEFAULT_THINK_TIME_MS=1000L`/`EVAL_DEPTH=12`/`EVAL_TIMEOUT_MS=5000L` (21–26); `start()` w/ `uci`→`uciok`, `isready`→`readyok` handshake (39–97); `getBestMove(fen)` + `getBestMove(fen, thinkTimeMs)` (99–125); `getEmbeddedBestMove` (127–147); `evaluate` (149–161); `waitForBestMove`/`waitForEvaluation` (192–219) | `app/src/jvmCommonMain/kotlin/com/example/myapplication/BaseStockfishEngine.kt` |
| `UciEvaluation` — `parseInfoScore` / `mateToCp` / `isWhiteToMove` / `toWhitePerspective`, pure commonMain; **reuse as-is** | `app/src/commonMain/kotlin/com/example/myapplication/UciEvaluation.kt` |
| `pickMoveStockfish` (engine call at :91, legality check, `pickMoveCPU` fallback) | `app/src/commonMain/kotlin/com/example/myapplication/Move.kt:74-114` |
| `evaluatePositionCp(engine, state)` → `engine?.evaluate(...) ?: materialBalanceCp(state)` | `app/src/commonMain/kotlin/com/example/myapplication/DrawAgreement.kt:27-28` |
| `GameViewModel` — plain class, own `scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` (:17); `attachEngine` (:38); `close` (:44); `startUserTurn` launches into `gameMoves: Job?` (:149-168); **`animationEnd` synchronously calls `tryBlackDrawOffer()` then `moveBlackWithEngine()`** (:170-180); `moveBlackWithEngine` (:182-193); `requestDrawOffer` wraps `offerDraw()` in `scope.launch` (:195-197); `offerDraw` (:199-217); `tryBlackDrawOffer` (:219-232); `declineDrawOffer` launches (:241-247); `moveCPU(turn, pickMove)` (:265-360) | `app/src/commonMain/kotlin/com/example/myapplication/GameViewModel.kt` |
| Wasm entry — `GameViewModel()` with no engine | `app/src/wasmJsMain/kotlin/com/example/myapplication/Main.kt` |
| Desktop entry — pattern to mirror: `CoroutineScope(Dispatchers.IO).launch { if (engine.start()) viewModel.attachEngine(engine) }` | `app/src/desktopMain/kotlin/com/example/myapplication/Main.kt` |
| "Stockfish: on/off" indicator reading `viewModel.stockfishEnabled` (:93, :196-199); board test tags `board_square_<SquareType>_<row>_<col>` | `app/src/commonMain/kotlin/com/example/myapplication/GameScreen.kt`; strings `stockfish_enabled`/`stockfish_disabled` in `app/src/commonMain/composeResources/values/strings.xml` |
| Build: `wasmJs { browser(); binaries.executable() }` (:54-57); `commonTest` has only `kotlin("test")` (:75-77); no explicit wasmJsMain/wasmJsTest blocks (they exist implicitly; `by getting` materializes them) | `app/build.gradle.kts` |
| Existing wasm static resources — served at server root by `wasmJsBrowserDevelopmentRun` and copied next to `index.html` by `wasmJsBrowserDistribution`; **no webpack config needed for extra files** | `app/src/wasmJsMain/resources/index.html` |
| CI runs `:app:check` — which **already includes `:app:wasmJsBrowserTest`** (Karma + headless Chrome; Node 20 is set up) — plus `:app:wasmJsBrowserDistribution`. New commonTest/wasmJsTest tests run in CI with **zero workflow changes** | `.github/workflows/android-tests.yml:83` |

Also verified: `kotlinx-coroutines-core:1.9.0` and `kotlinx-browser:0.5.0` are on the wasmJs classpath transitively via Compose. kotlinx-browser 0.5.0 declares `org.w3c.dom.Worker` (`postMessage`/`onmessage`/`terminate`), but `MessageEvent` could not be confirmed in its klib — so Step 7 self-declares minimal externals (10 lines, fully under our control) instead of depending on a WIP library's API surface.

## Step 1 — Vendor the engine files

Download the two **lite single-threaded** artifacts from the official stockfish.js release (nmrugg/stockfish.js — the de-facto official Stockfish wasm port, GPLv3, builds official-stockfish/Stockfish):

```
https://github.com/nmrugg/stockfish.js/releases/download/v18.0.0/stockfish-18-lite-single.js     (20,670 bytes)
https://github.com/nmrugg/stockfish.js/releases/download/v18.0.0/stockfish-18-lite-single.wasm  (7,295,411 bytes)
```

into `app/src/wasmJsMain/resources/stockfish/`, **keeping the filenames verbatim** — the `.wasm` filename is baked into the Emscripten-generated `.js` loader, which resolves it relative to the worker script's own URL. Renaming breaks loading silently.

Because they live under `wasmJsMain/resources`, both `wasmJsBrowserDevelopmentRun` and `wasmJsBrowserDistribution` serve/copy them automatically; the runtime URL is `stockfish/stockfish-18-lite-single.js` relative to the page. ~7 MB in git is well within repo norms (the vendored Android `.so` files are larger).

License: GPLv3, same as the vendored Android binaries; `docs/Stockfish-COPYING.txt` already covers it. Step 12 records provenance in `docs/Stockfish.md`.

## Step 2 — Gradle / version catalog

`gradle/libs.versions.toml`:

```toml
[versions]
kotlinxCoroutines = "1.9.0"   # matches the kotlinx-coroutines-core already on the classpath via Compose

[libraries]
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
```

`app/build.gradle.kts` `sourceSets`:

```kotlin
commonTest.dependencies {
    implementation(kotlin("test"))
    implementation(libs.kotlinx.coroutines.test)   // runTest for the now-suspend APIs
}

val wasmJsTest by getting {
    dependencies { implementation(compose.uiTest) }   // runComposeUiTest (Step 11)
}
```

Nothing else. No webpack/karma config, no CI workflow changes, no new wasmJsMain dependencies (the Worker externals in Step 7 are self-declared).

## Step 3 — Make `ChessEngine` suspend (commonMain)

`app/src/commonMain/kotlin/com/example/myapplication/ChessEngine.kt`:

```kotlin
interface ChessEngine {
    suspend fun getBestMove(fen: String): String?
    fun close()

    /** (keep existing kdoc) */
    suspend fun evaluate(fen: String): Int? = null
}
```

`close()` stays synchronous — `worker.terminate()` / process teardown are fire-and-forget, and `attachEngine`/`GameViewModel.close()` call it outside coroutines.

## Step 4 — `BaseStockfishEngine`: suspend signatures only (jvmCommonMain)

Do **not** restructure the reader thread / `LinkedBlockingQueue`. Only wrap:

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

override suspend fun getBestMove(fen: String): String? =
    withContext(Dispatchers.IO) { getBestMove(fen, DEFAULT_THINK_TIME_MS) }

// the blocking fun getBestMove(fen: String, thinkTimeMs: Long): String? stays exactly as-is

override suspend fun evaluate(fen: String): Int? = withContext(Dispatchers.IO) {
    /* existing body of evaluate(fen), verbatim (BaseStockfishEngine.kt:150-160) */
}
```

`start()` stays a blocking non-suspend member (it is not on the interface); desktop `Main.kt` already calls it on `Dispatchers.IO` and `MainActivity` synchronously — both unchanged. `StockfishEngine` (androidMain) and `DesktopStockfishEngine` (desktopMain) inherit; zero changes. Side benefit: Black's engine call no longer blocks the caller of `animationEnd` on Android/desktop.

## Step 5 — commonMain ripple

1. `Move.kt:74` — `suspend fun pickMoveStockfish(...)`; body unchanged (:91 becomes a suspend call).
2. `DrawAgreement.kt:27` — `suspend fun evaluatePositionCp(...)`; body unchanged.
3. `GameViewModel.kt`:
   - `moveCPU` (:265) → `suspend fun moveCPU(turn: Set = _gameState.value.turn, pickMove: suspend (...) -> SelectedMove)`. Body unchanged; existing lambda-literal call sites infer the suspend type.
   - `moveBlackWithEngine` (:182) → `private suspend fun`; `offerDraw` (:199) → `suspend fun`; `tryBlackDrawOffer` (:219) → `suspend fun ...: Boolean`.
   - `animationEnd` (:170) must launch, and the job must be tracked in the existing `gameMoves` so `startUserTurn`/`declineDrawOffer`/`close` cancel a pending Black engine move instead of racing it:

     ```kotlin
     fun animationEnd() {
         if (_animState.value.pieceToAnimate == null) return
         _animState.value = _animState.value.copy(pieceToAnimate = null)

         if (_gameState.value.turn == Set.BLACK) {
             gameMoves?.cancel()
             gameMoves = scope.launch {
                 if (!tryBlackDrawOffer()) moveBlackWithEngine()
             }
         } else {
             _viewState.value = _viewState.value.copy(moveButtonLock = false)
         }
     }
     ```
   - `startUserTurn` (:155), `requestDrawOffer` (:196) and `declineDrawOffer` (:245) already wrap the callees in `scope.launch` — they compile unchanged.

No UI changes anywhere: `GameScreen` calls only the non-suspend `startUserTurn()`/`animationEnd()`/`requestDrawOffer()`, and the "Stockfish: on" indicator flips automatically when `attachEngine` runs on wasm.

## Step 6 — `UciTransport` + `UciProtocolClient` (commonMain)

New file `app/src/commonMain/kotlin/com/example/myapplication/UciTransport.kt`:

```kotlin
/** Line-oriented pipe to a UCI engine. Implementations: WorkerUciTransport (wasm), FakeUciTransport (tests). */
interface UciTransport {
    /** Begin delivering engine output lines to [onLine]. Called once, before any send(). */
    fun start(onLine: (String) -> Unit)
    fun send(command: String)
    fun close()
}
```

New file `app/src/commonMain/kotlin/com/example/myapplication/UciProtocolClient.kt` — pure Kotlin + coroutines core (`Channel`, `Mutex`, `withTimeoutOrNull`; all wasm-safe). It mirrors `BaseStockfishEngine`'s semantics line-for-line (discard non-matching lines while waiting; track the last `parseInfoScore` until `bestmove`; consume the `bestmove` line) and adds two pieces of hardening: a `Mutex` so concurrent calls (draw-offer `evaluate` vs Black `bestMove`) serialize on the single worker, and `drainPending()` so a late reply from a timed-out call can't be misread as the next call's answer.

```kotlin
class UciProtocolClient(private val transport: UciTransport) {
    companion object {
        const val DEFAULT_THINK_TIME_MS = 1000L
        const val EVAL_DEPTH = 12
        const val HANDSHAKE_TIMEOUT_MS = 5000L
        const val REPLY_GRACE_MS = 5000L
        private val logger = Logger.withTag("UciProtocolClient")
    }

    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private var isReady = false

    suspend fun start(timeoutMs: Long = HANDSHAKE_TIMEOUT_MS): Boolean = mutex.withLock {
        if (isReady) return true
        transport.start { line -> incoming.trySend(line) }
        transport.send("uci")
        if (!awaitLinePrefix("uciok", timeoutMs)) return false
        transport.send("isready")
        if (!awaitLinePrefix("readyok", timeoutMs)) return false
        isReady = true
        true
    }

    suspend fun bestMove(fen: String, thinkTimeMs: Long = DEFAULT_THINK_TIME_MS): String? = mutex.withLock {
        if (!isReady) return null
        drainPending()
        transport.send("position fen $fen")
        transport.send("go movetime $thinkTimeMs")
        val line = awaitBestMoveLine(thinkTimeMs + REPLY_GRACE_MS) ?: return null
        parseBestMove(line)
    }

    /** Centipawns from WHITE's perspective; mirrors BaseStockfishEngine.evaluate. */
    suspend fun evaluate(fen: String, depth: Int = EVAL_DEPTH, timeoutMs: Long = REPLY_GRACE_MS): Int? = mutex.withLock {
        if (!isReady) return null
        drainPending()
        transport.send("position fen $fen")
        transport.send("go depth $depth")
        var lastEval: Int? = null
        val raw = withTimeoutOrNull(timeoutMs) {
            for (line in incoming) {
                UciEvaluation.parseInfoScore(line)?.let { lastEval = it }
                if (line.startsWith("bestmove")) break   // MUST consume bestmove (queue hygiene)
            }
            lastEval
        } ?: return null
        UciEvaluation.toWhitePerspective(raw, UciEvaluation.isWhiteToMove(fen))
    }

    fun close() {
        isReady = false
        transport.close()
        incoming.close()
    }

    private suspend fun awaitLinePrefix(prefix: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            for (line in incoming) if (line.startsWith(prefix)) return@withTimeoutOrNull true
            false
        } ?: false

    private suspend fun awaitBestMoveLine(timeoutMs: Long): String? =
        withTimeoutOrNull(timeoutMs) {
            for (line in incoming) if (line.startsWith("bestmove")) return@withTimeoutOrNull line
            null
        }

    /** Discard stale lines (e.g. a bestmove that arrived after a previous call timed out). */
    private fun drainPending() {
        while (incoming.tryReceive().isSuccess) { /* drop */ }
    }

    private fun parseBestMove(line: String): String? {
        val parts = line.split(" ")
        val idx = parts.indexOf("bestmove")
        val move = if (idx != -1) parts.getOrNull(idx + 1) else null
        return if (move == null || move == "(none)") null else move
    }
}
```

Design note: no internal `CoroutineScope` — all suspension happens in the caller's context, which makes virtual-time `runTest` testing trivial (Step 10).

## Step 7 — `WorkerUciTransport` + `WasmStockfishEngine` (wasmJsMain — thin shims)

New file `app/src/wasmJsMain/kotlin/com/example/myapplication/WorkerUciTransport.kt`. Self-declared minimal externals (primary approach — `MessageEvent` could not be confirmed in kotlinx-browser 0.5.0; if you prefer, `org.w3c.dom.Worker` from kotlinx-browser is a verified alternative for the Worker class itself, but then add an explicit `org.jetbrains.kotlinx:kotlinx-browser:0.5.0` dependency rather than relying on the transitive):

```kotlin
import co.touchlab.kermit.Logger

external class Worker(scriptURL: String) : JsAny {
    fun postMessage(message: JsString)
    fun terminate()
    var onmessage: (WorkerMessageEvent) -> Unit
}

external interface WorkerMessageEvent : JsAny {
    val data: JsAny?
}

class WorkerUciTransport(private val scriptUrl: String) : UciTransport {
    private var worker: Worker? = null

    companion object {
        private val logger = Logger.withTag("WorkerUciTransport")
    }

    override fun start(onLine: (String) -> Unit) {
        val w = Worker(scriptUrl)
        w.onmessage = { event ->
            event.data?.let { onLine(it.toString()) }   // stockfish.js posts plain JS strings
        }
        worker = w
    }

    override fun send(command: String) {
        worker?.postMessage(command.toJsString())
    }

    override fun close() {
        worker?.terminate()
        worker = null
    }
}
```

Kotlin/Wasm interop cautions:
- `postMessage` must receive a `JsString` (`command.toJsString()`); a raw Kotlin `String` won't compile against a `JsAny`-typed external.
- `event.data?.toString()` unwraps a `JsString` payload; if the compiler balks, use `event.data.unsafeCast<JsString>().toString()`.
- If the external `var onmessage` property with a Kotlin function type misbehaves, fall back to declaring `fun addEventListener(type: JsString, callback: (WorkerMessageEvent) -> Unit)` on the external class and registering `"message"`.
- A bad script URL does **not** throw from the `Worker` constructor — failure is asynchronous silence. Engine readiness is decided solely by the `uciok` handshake timeout.

New file `app/src/wasmJsMain/kotlin/com/example/myapplication/WasmStockfishEngine.kt`:

```kotlin
/** Keep in sync with the vendored filename under app/src/wasmJsMain/resources/stockfish/ (Step 1). */
private const val STOCKFISH_WORKER_URL = "stockfish/stockfish-18-lite-single.js"

class WasmStockfishEngine(
    transport: UciTransport = WorkerUciTransport(STOCKFISH_WORKER_URL)
) : ChessEngine {
    private val client = UciProtocolClient(transport)

    /** False on handshake timeout (e.g. worker file 404) — caller then skips attachEngine. */
    suspend fun start(): Boolean = client.start()

    override suspend fun getBestMove(fen: String): String? = client.bestMove(fen)

    override suspend fun evaluate(fen: String): Int? = client.evaluate(fen)

    override fun close() = client.close()
}
```

Note `evaluate` working on wasm means the draw-agreement feature (engine accept/decline/proactive offers) now uses real evaluation on the web instead of the material fallback — for free.

## Step 8 — Wire up `Main.kt` (wasmJsMain)

There is no `Dispatchers.IO` on wasm and nothing blocks, so a `LaunchedEffect` mirrors the desktop pattern:

```kotlin
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.title = "Chess"
    ComposeViewport("ComposeTarget") {
        val viewModel = remember { GameViewModel() }
        LaunchedEffect(Unit) {
            val engine = WasmStockfishEngine()
            if (engine.start()) {
                viewModel.attachEngine(engine)   // flips "Stockfish: on"; viewModel now owns engine.close()
            } else {
                Logger.w("Main") { "Stockfish wasm worker failed to start; using CPU fallback" }
                engine.close()
            }
        }
        DisposableEffect(Unit) {
            onDispose { viewModel.close() }      // closes the attached engine too
        }

        MyApplicationTheme(darkTheme = false) {
            ChessApp(viewModel = viewModel)
        }
    }
}
```

Failure path (404, ad-blocker, ancient browser) = today's behavior exactly: no engine attached, "Stockfish: off", `pickMoveCPU`. If the player moves before the ~1–3 s worker init finishes, that one Black reply uses the CPU fallback (`chessEngine == null`) — same readiness race desktop already has.

## Step 9 — Update existing tests for the suspend ripple

Pattern: `import kotlinx.coroutines.test.runTest`, wrap affected test bodies in `= runTest { ... }`, add `suspend` to anonymous `ChessEngine` overrides. Add one shared helper, e.g. new `app/src/commonTest/kotlin/com/example/myapplication/TestUtil.kt`:

```kotlin
/** Real-time wait for a state change produced by the ViewModel's own (non-test) scope.
 *  withContext(Dispatchers.Default) escapes runTest's virtual time. */
suspend fun GameViewModel.awaitState(timeoutMs: Long = 5_000, predicate: (GameUiState) -> Boolean): GameUiState =
    withContext(Dispatchers.Default) { withTimeout(timeoutMs) { gameState.first(predicate) } }
```

File-by-file (anchors verified):

- `commonTest/MoveTest.kt` — mock at :54 gets `override suspend fun getBestMove(...)`; wrap `pickMoveStockfish parses promotion from engine` (:48) in `runTest`.
- `commonTest/CastlingTest.kt` — `moveCPU` test (:121) and the `pickMoveStockfish` test with the anonymous engine (:160, :165) → `runTest` + suspend mock.
- `commonTest/GameViewModelTest.kt` — tests calling `viewModel.moveCPU(...)` at :11, :27, :44/:51, :233/:247 → `runTest`.
- `commonTest/DrawConditionsTest.kt` — every test using `vm.moveCPU` (:17, :78, :98–:126) → `runTest`.
- `commonTest/EnPassantTest.kt` — :76, :118 → `runTest`.
- `commonTest/PromotionTest.kt` — `moveCPU` calls (:96, :106) → `runTest`. `testMatePromotion` (:140): `animationEnd()` is now async — replace the immediate assert with `viewModel.awaitState { it.winState == WinState.WHITE }` inside `runTest`, and update the now-stale comment "runs moveCPU(BLACK) synchronously since no engine".
- `commonTest/DrawAgreementTest.kt` — `mockEngine` (:11–:17): suspend overrides. Tests calling `viewModel.offerDraw()` directly (:104, :115, :126, :136, :148, :159, :177) → `runTest { ... }` (a direct suspend call stays deterministic; subsequent assertions remain valid). The three `animationEnd()` tests become await-based:
  - `testBlackProactivelyOffers` (:192) → `awaitState { it.drawOffer == Set.BLACK }`, then assert `winState == NONE`.
  - `testBlackProactivelyOffers_Autoplay_NoOffer` (:207) and `testBlackDrawOfferCooldown` (:220) → Black now moves in the background; `awaitState { it.turn == Set.WHITE }` (Black's move completed) and then assert `drawOffer == null`. Asserting null immediately after `animationEnd()` would pass vacuously before the coroutine runs.
- `androidDeviceTest/DrawOfferScreenTest.kt` — `mockEngine` (:27–:33): add `suspend` to `getBestMove`/`evaluate`. The tests themselves already `waitUntil` and need no other change.
- `androidDeviceTest/GameScreenTest.kt` — `testStalemate` calls `moveCPU` inside `runOnIdle` (:82–:83): wrap as `composeTestRule.runOnIdle { runBlocking { viewModel.moveCPU { ... } } }` (JVM instrumentation test; the pickMove lambda is never reached because stalemate is detected first).

Untouched: `FenConverterTest`, `UciEvaluationTest`, `UciMoveConverterTest`, `DrawConditionsTest`'s pure-function tests, `MoveTest`'s first test, all playerMove-only tests.

## Step 10 — New unit tests: `UciProtocolClientTest` (commonTest)

New files `app/src/commonTest/kotlin/com/example/myapplication/FakeUciTransport.kt` and `UciProtocolClientTest.kt`. These run on every target via `./gradlew test` / `:app:check` — including `wasmJsBrowserTest`, which is the point.

```kotlin
class FakeUciTransport : UciTransport {
    val sent = mutableListOf<String>()
    var closed = false
        private set
    private var onLine: ((String) -> Unit)? = null

    override fun start(onLine: (String) -> Unit) { this.onLine = onLine }
    override fun send(command: String) { sent += command }
    override fun close() { closed = true }
    fun emit(line: String) = onLine!!.invoke(line)
}
```

Test pattern — the client suspends in the caller's context, so `runTest` + `runCurrent()`/`advanceUntilIdle()` drive it deterministically in virtual time:

```kotlin
@Test
fun handshakeSucceeds() = runTest {
    val t = FakeUciTransport()
    val client = UciProtocolClient(t)
    val result = async { client.start() }
    runCurrent()
    assertEquals(listOf("uci"), t.sent)
    t.emit("Stockfish 18 by the Stockfish developers (see AUTHORS file)")
    t.emit("uciok")
    runCurrent()
    assertEquals("isready", t.sent.last())
    t.emit("readyok")
    assertTrue(result.await())
}
```

Cases (name → assertion):

1. `handshakeSucceeds` — above.
2. `handshakeTimesOutWithoutUciok` — emit nothing; `advanceUntilIdle()`; `start()` returns false (virtual 5 s timeout).
3. `handshakeTimesOutWithoutReadyok` — emit `uciok` only → false.
4. `bestMoveSendsPositionAndGoAndParsesReply` — after handshake, call `bestMove(fen)`; assert `sent` contains `"position fen $fen"` and `"go movetime 1000"`; emit `info depth 10 score cp 23 pv e2e4` then `bestmove e2e4 ponder e7e5` → returns `"e2e4"`.
5. `bestMoveParsesPromotion` — `bestmove a7a8q` → `"a7a8q"`.
6. `bestMoveReturnsNullForNone` — `bestmove (none)` → null (checkmate/stalemate position).
7. `bestMoveIgnoresNoiseLines` — banner/blank/`info string NNUE evaluation using ...` lines before the bestmove are skipped.
8. `bestMoveTimesOut` — no reply; `advanceUntilIdle()` → null.
9. `callsBeforeStartReturnNull` — `bestMove`/`evaluate` without handshake → null, nothing sent.
10. `evaluateReturnsLastScoreWhitePerspective_whiteToMove` — FEN with `w`; emit `info ... score cp 30`, `info ... score cp -45`, `bestmove e2e4` → `-45`.
11. `evaluateNegatesForBlackToMove` — same lines, FEN with `b` → `45`.
12. `evaluateMapsMateScores` — `info depth 12 score mate 3 ...` + bestmove, white to move → `99997`; `mate -2` → `-99998` (matches `UciEvaluation.mateToCp`).
13. `evaluateTimesOutWithoutBestmove` — only info lines → null after `advanceUntilIdle()`.
14. `evaluateConsumesBestmove_thenBestMoveGetsFreshReply` — run `evaluate` to completion, then `bestMove`; emit only the new `bestmove d2d4` → `"d2d4"` (queue hygiene).
15. `staleLinesDrainedBeforeNewRequest` — emit a stray `bestmove a1a2` *before* calling `bestMove`; then reply `bestmove e2e4` → `"e2e4"`.
16. `closeClosesTransport` — `close()` → `t.closed` is true.

## Step 11 — New wasm UI tests (wasmJsTest)

New file `app/src/wasmJsTest/kotlin/com/example/myapplication/WasmGameScreenTest.kt`, using `@OptIn(ExperimentalTestApi::class)` + `runComposeUiTest` (dependency `compose.uiTest` from Step 2; `kotlin("test")` is inherited from commonTest, and commonTest declarations like `FakeUciTransport` are visible here). These run under `:app:wasmJsBrowserTest` — i.e. inside `:app:check` in CI — in headless Chrome.

Scripted fake engine (inline in the test file):

```kotlin
private class ScriptedEngine(private val move: String?) : ChessEngine {
    var lastFen: String? = null
        private set
    override suspend fun getBestMove(fen: String): String? { lastFen = fen; return move }
    override fun close() {}
}
```

Tests:

1. `stockfishIndicatorOnAfterEngineAttach` — `GameViewModel()` + `attachEngine(ScriptedEngine("e7e5"))`; `setContent { MyApplicationTheme { ChessApp(viewModel) } }`; `waitUntil { onAllNodesWithText("Stockfish: on").fetchSemanticsNodes().isNotEmpty() }` (waitUntil rather than assertExists — compose string resources load asynchronously on wasm); assert displayed.
2. `stockfishIndicatorOffWithoutEngine` — fresh `GameViewModel()`; wait for and assert "Stockfish: off".
3. `blackRepliesWithScriptedEngineMove` — attach `ScriptedEngine("e7e5")`; play 1.e4 via the board tags (same pattern as `androidDeviceTest/GameScreenTest.kt`): click `board_square_WhitePiece_6_4` (e2 pawn), then `board_square_PossibleMove_4_4` (e4); `waitUntil(timeoutMillis = 10_000) { viewModel.gameState.value.positionsBlack.contains(Pair(3, 4)) }` (Black pawn on e5 — model-level predicate avoids waiting out Black's animation), then assert `engine.lastFen != null` (Stockfish path was consulted, not the CPU fallback).
4. `engineNullFallsBackToCpuMove` — attach `ScriptedEngine(null)`; same white move; `waitUntil(10_000) { viewModel.gameState.value.turn == Set.WHITE }` → Black still moved (the engine-failure → `pickMoveCPU` fallback works end-to-end on wasm).

**Real-engine smoke testing is deliberately manual, not CI** (decision recorded with the repo owner): Karma does not serve `wasmJsMain/resources` to tests, so a real-worker test would need `karma.config.d` file-serving/proxy/MIME plumbing, and a 7 MB wasm compile plus 1 s movetime in headless Chrome is a flakiness magnet. The manual smoke check is in Verification below.

## Step 12 — Documentation

- `docs/Stockfish.md` — add a "WASM build (web target)" section: source = nmrugg/stockfish.js release v18.0.0; exact filenames + sizes (`stockfish-18-lite-single.js`, 20,670 B; `stockfish-18-lite-single.wasm`, 7,295,411 B); vendored at `app/src/wasmJsMain/resources/stockfish/`; why lite-single (no SharedArrayBuffer/COOP/COEP → works on static hosts; full single-threaded wasm is 113 MB, over GitHub's 100 MB limit); do not rename the files (wasm name baked into the js loader); GPLv3 covered by `docs/Stockfish-COPYING.txt`.
- `CLAUDE.md` — Engine architecture section: `ChessEngine` is now a *suspend* interface; replace "Wasm — no engine" with `UciProtocolClient` (commonMain, transport-abstracted, fake-tested) + `WorkerUciTransport`/`WasmStockfishEngine` (wasmJsMain) + vendored resources path; note `animationEnd` now launches Black's reply as a coroutine tracked in `gameMoves`.

## Verification

```bash
./gradlew :app:desktopTest                 # fast loop: all commonTest incl. UciProtocolClientTest on JVM
./gradlew test                             # unit tests on all targets
./gradlew :app:wasmJsBrowserTest           # protocol tests + new wasm UI tests in headless Chrome
./gradlew :app:wasmJsBrowserDistribution   # then check stockfish/*.js and *.wasm sit next to index.html in the dist
./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS
./gradlew :app:connectedAndroidDeviceTest  # needs device/emulator (CI: API 35)
```

Manual smoke (replaces a real-engine CI test):

1. `./gradlew :app:wasmJsBrowserDevelopmentRun` → page shows "Stockfish: on" within a few seconds; DevTools Network shows both `stockfish/*` files loading; play 1.e4 and confirm Black replies with a sensible opening move (e.g. e7e5/c7c5 — not a random rook shuffle).
2. `./gradlew :app:desktopRun` (system stockfish installed) → behavior identical to before the suspend refactor.

## Risks / implementer notes

1. **Test determinism around the async `animationEnd`** is the highest-churn risk. Rule: anything previously asserted immediately after `animationEnd()` must go through `awaitState`. Note `awaitState` deliberately uses `withContext(Dispatchers.Default)` — a bare `withTimeout` inside `runTest` runs on virtual time and would fire before the ViewModel's real `Dispatchers.Default` work completes.
2. **Kotlin/Wasm JS interop** is confined to `WorkerUciTransport`: `toJsString()` for outgoing messages, `data?.toString()` for incoming, `addEventListener` fallback if the external `var onmessage` property gives trouble. Everything else is pure Kotlin.
3. **Worker file 404 / load failure** → no `uciok` → `start()` returns false after 5 s → engine never attached → "Stockfish: off" + `pickMoveCPU`. No crash path; identical to today's web behavior.
4. **Do not rename the vendored files** — the `.wasm` filename is a string constant inside the Emscripten `.js`. `STOCKFISH_WORKER_URL` must match the vendored `.js` name exactly.
5. **`.wasm` MIME type**: if a host serves the wrong content-type, Emscripten falls back from streaming compilation to ArrayBuffer instantiation — slower but functional. Webpack dev server and GitHub Pages are fine.
6. **UCI queue hygiene**: `evaluate` must consume its `bestmove` line, and `drainPending()` must run before each request — otherwise a late reply from a timed-out call is misread as the next call's answer. (This is a latent flaw in `BaseStockfishEngine` too; deliberately not fixed there to keep the JVM diff at signatures only.)
7. **Readiness race**: the first Black move may use the CPU fallback while the worker initializes — acceptable, documented, and the indicator only says "on" once attached.
8. **Wasm UI test flakiness**: prefer model-level `waitUntil` predicates (`viewModel.gameState.value...`) over animation-dependent node assertions; use 10 s timeouts; if `runComposeUiTest`'s clock stalls the 500 ms move tween, add `mainClock.advanceTimeBy(600)` before the `waitUntil`.
9. **CI impact**: +~7 MB repo size; `:app:check` gains the new tests (seconds); **no workflow file changes**.
10. **Existing `GameViewModelTest` lambdas** pass the non-suspend `pickMoveRandom` where a `suspend` lambda is now expected — this compiles as-is; only tests that *call* the now-suspend functions need `runTest`.
