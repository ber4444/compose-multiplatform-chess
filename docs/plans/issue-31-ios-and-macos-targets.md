# iOS and macOS Desktop Targets — Implementation Plan (GitHub issue #31)

> Self-contained plan for an AI coding agent. Repo: `compose-multiplatform-chess`. Paths relative to repo root. Suggested branch: `ios-macos-targets`.

## Context and decisions (final — do not revisit)

[Issue #31](https://github.com/ber4444/compose-multiplatform-chess/issues/31): add iOS and Mac desktop targets (currently Android, Linux desktop JVM, Wasm). The issue's hints were scrutinized; two are corrected here:

1. **macOS desktop reuses the existing `jvm("desktop")` target** — Compose Multiplatform has no supported native-macOS UI (open feature request [CMP-4580](https://youtrack.jetbrains.com/issue/CMP-4580)), and the JVM desktop app already runs on macOS. Work: `TargetFormat.Dmg`, Stockfish path probing (Homebrew installs aren't on a Finder-launched app's PATH), macOS CI. Do **not** add `macosX64`/`macosArm64` Kotlin targets.
2. **iOS = new `iosArm64` + `iosSimulatorArm64` targets** in `:app` producing one static framework (`ChessApp`), plus a new `iosApp/` Xcode project (SwiftUI hosting `ComposeUIViewController`).
3. **iOS engine is Swift-side**: the hint's "StockfishKit" doesn't exist; use [ChessKitEngine](https://github.com/chesskit-app/chesskit-engine) (SPM, bundles Stockfish 17). iOS cannot spawn processes, so `BaseStockfishEngine` is unusable there; a Swift class conforms to the Kotlin `ChessEngine` interface exported through the framework and is injected via the existing `GameViewModel.attachEngine(...)` pattern.
4. **Stockfish 17 NNUE files committed to the repo** under `iosApp/iosApp/Resources/` and configured at startup via UCI `setoption name EvalFile` / `EvalFileSmall` (precedent: `app/src/androidMain/jniLibs/*/libstockfish.so`; see `docs/Stockfish.md`).

## Key existing code and facts (verified)

| What | Where |
|---|---|
| `ChessEngine` **public** interface: `getBestMove(fen): String?`, `close()`, `evaluate(fen): Int? = null` | `app/src/commonMain/kotlin/com/example/myapplication/ChessEngine.kt` |
| `UciEvaluation` **public object** (`parseInfoScore`, `mateToCp`, `isWhiteToMove`, `toWhitePerspective`) — exported in the iOS framework ⇒ callable from Swift as `UciEvaluation.shared.*`; **reuse it, don't reimplement score math in Swift** | `app/src/commonMain/kotlin/com/example/myapplication/UciEvaluation.kt` |
| `GameViewModel` — plain class, own `CoroutineScope(SupervisorJob() + Dispatchers.Default)`; `attachEngine(ChessEngine?)`; `close()` also closes the engine; engine calls run on `Dispatchers.Default` (never main) | `app/src/commonMain/kotlin/com/example/myapplication/GameViewModel.kt` |
| Desktop entry point (start engine on `Dispatchers.IO`, attach on success, `onDispose` closes) — the pattern to mirror | `app/src/desktopMain/kotlin/com/example/myapplication/Main.kt` |
| No-engine entry point (`remember { GameViewModel() }` + `DisposableEffect` close) | `app/src/wasmJsMain/kotlin/com/example/myapplication/Main.kt` |
| `DesktopStockfishEngine` — currently returns bare `"stockfish"` (PATH lookup); `resolveExecutablePath()` returning null = embedded CPU fallback | `app/src/desktopMain/kotlin/com/example/myapplication/DesktopStockfishEngine.kt`, `BaseStockfishEngine.kt` (jvmCommonMain) |
| Gradle: `android` (via `com.android.kotlin.multiplatform.library`), `jvm("desktop")`, `wasmJs`; **manual hierarchy** (`kotlin.mpp.applyDefaultHierarchyTemplate=false`); `jvmCommonMain by creating { dependsOn(commonMain.get()) }` is the wiring style to copy; `targetFormats(TargetFormat.Deb)` at line ~131; Android resource reflection hacks at lines ~110–122 and ~136–145 — **leave untouched** | `app/build.gradle.kts` |
| With the template off, only *intermediate* source sets need manual `dependsOn`; platform default source sets still inherit commonMain/commonTest (evidence: `wasmJsMain` has no explicit wiring; `:app:desktopTest` runs commonTest today) | `app/build.gradle.kts` |
| UI-test conventions: testTags `chess_board`, `board_square_<SquareType>_<row>_<col>` (row = 8 − rank; e2 pawn = `WhitePiece_6_4`), `offer_draw_button`, `winnerText`; `waitUntil` + `onAllNodesWithTag(...).fetchSemanticsNodes(atLeastOneRootRequired = false)` | `app/src/androidDeviceTest/kotlin/com/example/myapplication/GameScreenTest.kt` |
| 10 commonTest classes (kotlin.test) — run on the iOS simulator automatically once the target exists | `app/src/commonTest/kotlin/com/example/myapplication/` |
| CI: single ubuntu job; build step `./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution` then emulator tests | `.github/workflows/android-tests.yml` |

**ChessKitEngine facts (verified against source tags 0.6.0 / 0.7.0):**

- `Engine(type: .stockfish)`; `start()` async; `send(command:)` async; `responseStream: AsyncStream<EngineResponse>?` — **subscribe before `start()`**.
- `EngineCommand`: `.setoption(id:value:)`, `.position(.fen(String))`, `.go(depth:)` / `.go(movetime:)`, `.stop`. `EngineResponse`: `.readyok`, `.bestmove(move:ponder:)`, `.info(Info)` with `score.cp: Double?` / `score.mate: Int?` (side-to-move perspective).
- Platforms `.iOS(.v16)` / `.macOS(.v13)`. **Pin `0.6.0` exactly** (swift-tools 6.0, Xcode 16+); `0.7.0` needs swift-tools 6.2 (Xcode 26+). API used here is identical in both.
- NNUE files NOT bundled: `nn-1111cefa1111.nnue` (~45 MB) and `nn-37f18f62d772.nnue` (~3 MB) — Stockfish 17 defaults.

---

## Step 1 — macOS desktop: DMG packaging + Stockfish path probing

**1a.** `app/build.gradle.kts` (~line 131): `targetFormats(TargetFormat.Deb, TargetFormat.Dmg)`. `packageDistributionForCurrentOS` builds only the host-matching format, so ubuntu CI still produces the `.deb` and macOS produces the `.dmg`.

**1b.** Rewrite `app/src/desktopMain/kotlin/com/example/myapplication/DesktopStockfishEngine.kt` with a testable seam (pure function + exists-predicate, so unit tests need no real binaries):

```kotlin
/** Common absolute install locations, probed in order before falling back to a PATH lookup. */
internal val STOCKFISH_CANDIDATE_PATHS = listOf(
    "/opt/homebrew/bin/stockfish", // macOS Apple Silicon (Homebrew)
    "/usr/local/bin/stockfish",    // macOS Intel (Homebrew)
    "/usr/games/stockfish",        // Debian/Ubuntu
    "/usr/bin/stockfish",          // Fedora/Arch
)
internal const val STOCKFISH_PATH_FALLBACK = "stockfish"

internal fun resolveStockfishPath(
    candidates: List<String> = STOCKFISH_CANDIDATE_PATHS,
    isExecutableFile: (String) -> Boolean = { p -> File(p).let { it.isFile && it.canExecute() } },
): String = candidates.firstOrNull(isExecutableFile) ?: STOCKFISH_PATH_FALLBACK

class DesktopStockfishEngine : BaseStockfishEngine() {
    override fun resolveExecutablePath(): String = resolveStockfishPath()
}
```

Bare-PATH fallback stays last: preserves today's behavior for custom installs; if that also fails, `start()` returns false and `Main.kt` logs and leaves the engine unattached (unchanged).

**1c. Unit tests** — new `app/src/desktopTest/kotlin/com/example/myapplication/DesktopStockfishEngineTest.kt` (directory doesn't exist yet; the default source set does and inherits commonTest + `kotlin("test")`):

1. `returnsFirstExecutableCandidate` — predicate true only for the 2nd entry → returns it.
2. `prefersEarlierCandidateWhenSeveralExist` — true for entries 1 and 3 → returns entry 1.
3. `fallsBackToPathLookupWhenNoCandidateMatches` — always false → `"stockfish"`.
4. `defaultPredicateRejectsNonExecutableAndMissingFiles` — real temp files (`File.createTempFile`, one `setExecutable(true)`, one not, one missing path); guard `if (!executable.canExecute()) return` for exotic filesystems; delete in `finally`.
5. `defaultCandidateListIsOrderedHomebrewFirst` — regression guard on list contents/order.

`internal` in desktopMain is visible from desktopTest (default main↔test association).

## Step 2 — Gradle: iOS targets, framework, source sets

All in `app/build.gradle.kts`. Add `import org.jetbrains.compose.ExperimentalComposeLibrary`.

**2a. Targets** — between `jvm("desktop")` and `wasmJs`:

```kotlin
iosArm64 {
    binaries.framework { baseName = "ChessApp"; isStatic = true }
}
iosSimulatorArm64 {
    binaries.framework { baseName = "ChessApp"; isStatic = true }
    // KGP's default simulator device often doesn't exist on current Xcode images.
    testRuns.configureEach {
        deviceId = providers.gradleProperty("iosSimulatorDeviceId").getOrElse("iPhone 16")
    }
}
```

`:app` is the framework module, so all its **public** API (`ChessEngine`, `GameViewModel`, `UciEvaluation`, `MainViewController`) is exported automatically — no `export(...)` needed.

**2b. Source sets** — inside `sourceSets { }` after `desktopMain`:

```kotlin
val iosMain by creating { dependsOn(commonMain.get()) }
val iosArm64Main by getting { dependsOn(iosMain) }
val iosSimulatorArm64Main by getting { dependsOn(iosMain) }

val iosSimulatorArm64Test by getting {
    dependencies {
        @OptIn(ExperimentalComposeLibrary::class)
        implementation(compose.uiTest)
    }
}
```

Decisions baked in: one intermediate `iosMain` (mirrors `jvmCommonMain` style), **no** `appleMain` split; **no** `iosTest` intermediate — commonTest flows into both iOS test compilations automatically, and `compose.uiTest` goes only into `iosSimulatorArm64Test` so android/desktop/wasm test classpaths stay unchanged and no device-arm64 test binary links UI-test deps.

**2c.** Only if needed (verify on first CI run, don't pre-empt): `kotlin.native.ignoreDisabledTargets=true` in `gradle.properties` if the ubuntu job complains about disabled Apple targets; raise `org.gradle.jvmargs` to `-Xmx4096m` if framework links OOM.

## Step 3 — Kotlin iOS entry point

New `app/src/iosMain/kotlin/com/example/myapplication/MainViewController.kt`:

```kotlin
/**
 * iOS entry point. The engine is created and started on the Swift side
 * (StockfishChessEngine) and injected here, mirroring desktop Main.kt.
 * Pass null to play against the built-in CPU.
 */
fun MainViewController(engine: ChessEngine?): UIViewController = ComposeUIViewController {
    val viewModel = remember { GameViewModel() }
    DisposableEffect(Unit) {
        viewModel.attachEngine(engine)
        onDispose { viewModel.close() } // also closes the attached engine
    }
    MyApplicationTheme { ChessApp(viewModel = viewModel) }
}
```

Design note: unlike desktop (attach only after `start()` succeeds), a UIViewController must be returned synchronously, and the bundled engine can't go missing like a system binary; the Swift adapter starts asynchronously and is attached immediately. If startup fails anyway, `getBestMove` returns nil and `pickMoveStockfish` (Move.kt) falls back to `pickMoveCPU` — the game stays playable. Callable from Swift as `MainViewControllerKt.MainViewController(engine:)`.

## Step 4 — NNUE network files (vendored)

```bash
mkdir -p iosApp/iosApp/Resources
curl -L -o iosApp/iosApp/Resources/nn-1111cefa1111.nnue https://tests.stockfishchess.org/api/nn/nn-1111cefa1111.nnue
curl -L -o iosApp/iosApp/Resources/nn-37f18f62d772.nnue https://tests.stockfishchess.org/api/nn/nn-37f18f62d772.nnue
# Integrity: filename embeds the first 12 sha256 hex chars
shasum -a 256 iosApp/iosApp/Resources/nn-1111cefa1111.nnue | cut -c1-12   # → 1111cefa1111
shasum -a 256 iosApp/iosApp/Resources/nn-37f18f62d772.nnue | cut -c1-12   # → 37f18f62d772
```

Commit raw, no LFS (both under GitHub's 100 MB limit; matches `libstockfish.so` precedent). **Verification hook:** these are Stockfish 17.0 defaults — during XCTest bring-up (Step 6), enable `loggingEnabled: true` once and confirm the engine's reported `option name EvalFile ... default nn-….nnue` names match; if ChessKitEngine's bundled Stockfish expects different nets, vendor those instead and update every filename reference.

## Step 5 — `iosApp/` Xcode project

**Layout:**

```
iosApp/
├── project.yml                    # XcodeGen spec — source of truth
├── iosApp.xcodeproj/              # generated by `xcodegen generate`, COMMITTED
├── iosApp/
│   ├── iOSApp.swift
│   ├── ContentView.swift
│   ├── StockfishChessEngine.swift
│   ├── Info.plist
│   ├── Assets.xcassets/           # minimal AppIcon set
│   └── Resources/                 # the two .nnue files
└── iosAppTests/
    └── StockfishChessEngineTests.swift
```

Generation: **XcodeGen** (`brew install xcodegen`; runs without Xcode). Commit both `project.yml` and the generated `.xcodeproj` (xcuserdata gitignored) so plain-Xcode users and CI need no extra tooling. Fallback if XcodeGen unavailable: hand-write `project.pbxproj` following the JetBrains KMP wizard iosApp template plus an `XCRemoteSwiftPackageReference` for ChessKitEngine.

**`iosApp/project.yml`:**

```yaml
name: iosApp
options:
  bundleIdPrefix: com.example.myapplication
  deploymentTarget:
    iOS: "16.0"          # floor set by ChessKitEngine (.iOS(.v16))
  createIntermediateGroups: true
settings:
  base:
    ENABLE_USER_SCRIPT_SANDBOXING: "NO"   # required by embedAndSignAppleFrameworkForXcode
packages:
  ChessKitEngine:
    url: https://github.com/chesskit-app/chesskit-engine
    exactVersion: 0.6.0   # 0.7.0 needs swift-tools 6.2 (Xcode 26+); bump deliberately
targets:
  iosApp:
    type: application
    platform: iOS
    sources:
      - path: iosApp
        excludes: ["Resources/**"]
      - path: iosApp/Resources
        buildPhase: resources          # .nnue files must land in the app bundle
    dependencies:
      - package: ChessKitEngine
    info:
      path: iosApp/Info.plist
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: com.example.myapplication
        FRAMEWORK_SEARCH_PATHS: "$(inherited) $(SRCROOT)/../app/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)"
        OTHER_LDFLAGS: "$(inherited) -framework ChessApp"
        TARGETED_DEVICE_FAMILY: "1,2"
        CODE_SIGN_STYLE: Automatic
    preBuildScripts:
      - name: Compile Kotlin Framework
        basedOnDependencyAnalysis: false
        script: |
          if [ -z "${JAVA_HOME:-}" ]; then export JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true); fi
          cd "$SRCROOT/.."
          ./gradlew :app:embedAndSignAppleFrameworkForXcode
  iosAppTests:
    type: bundle.unit-test
    platform: iOS
    sources: [iosAppTests]
    dependencies:
      - target: iosApp
    settings:
      base:
        FRAMEWORK_SEARCH_PATHS: "$(inherited) $(SRCROOT)/../app/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)"
        TEST_HOST: "$(BUILT_PRODUCTS_DIR)/iosApp.app/iosApp"
        BUNDLE_LOADER: "$(TEST_HOST)"
schemes:
  iosApp:
    build:
      targets: { iosApp: all }
    run:
      config: Debug
    test:
      config: Debug
      targets: [iosAppTests]
```

Mechanics that matter: the run-script phase must be the **first** build phase (`preBuildScripts` guarantees it) — `embedAndSignAppleFrameworkForXcode` builds the framework for the right SDK/arch and copies it plus the Compose resources into the bundle, driven by Xcode env vars; it never works standalone. Hosted unit tests (`TEST_HOST`/`BUNDLE_LOADER`) make `Bundle.main` the app bundle so XCTests exercise the exact NNUE lookup the app uses. The `schemes:` block produces the shared scheme `xcodebuild -scheme iosApp` needs in CI.

**Swift shell** — `iOSApp.swift` (standard `@main App` with `WindowGroup { ContentView() }`) and `ContentView.swift`:

```swift
import SwiftUI
import UIKit
import ChessApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(engine: StockfishChessEngine())
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView().ignoresSafeArea() // ChessApp applies WindowInsets.safeDrawing itself
    }
}
```

`Info.plist`: minimal — `CFBundleExecutable=$(EXECUTABLE_NAME)`, `CFBundleIdentifier=$(PRODUCT_BUNDLE_IDENTIFIER)`, `CFBundleName=Chess`, `CFBundlePackageType=APPL`, version 1.0.0/1, `UILaunchScreen=<dict/>`, portrait + both landscapes.

**Root `.gitignore` additions:**

```
iosApp/iosApp.xcodeproj/xcuserdata
iosApp/iosApp.xcodeproj/project.xcworkspace/xcuserdata
iosApp/build/*
```

## Step 6 — Swift engine adapter + XCTests

New `iosApp/iosApp/StockfishChessEngine.swift`. Bridging facts: the Kotlin interface exports as an ObjC protocol, so the Swift class subclasses `NSObject` and implements **all three** methods (Kotlin default impls don't carry over to ObjC protocols); Kotlin `Int?` boxes to `KotlinInt?`; Kotlin objects surface as `.shared`. Kotlin calls these from `Dispatchers.Default` worker threads — blocking with semaphores is safe there; guard against main-thread use (deadlock).

```swift
import Foundation
import ChessApp        // Kotlin framework (ChessEngine protocol, UciEvaluation, KotlinInt)
import ChessKitEngine

/// Bridges the synchronous Kotlin ChessEngine interface to ChessKitEngine's async API.
/// getBestMove/evaluate block and MUST be called off the main thread
/// (GameViewModel calls them from Dispatchers.Default).
final class StockfishChessEngine: NSObject, ChessEngine {

    private static let moveTimeMs = 1_000          // mirrors BaseStockfishEngine think time
    private static let evalDepth: Int = 12         // mirrors BaseStockfishEngine.EVAL_DEPTH
    private static let readyTimeout: TimeInterval = 15
    private static let responseTimeout: TimeInterval = 8

    private let engine = Engine(type: .stockfish)
    private let requestLock = NSLock()             // serializes getBestMove/evaluate
    private let stateQueue = DispatchQueue(label: "stockfish.adapter.state")
    private let readySemaphore = DispatchSemaphore(value: 0)
    private var isReady = false
    private var isClosed = false
    private var pendingCompletion: ((String?) -> Void)?
    private var lastRawScoreCp: Int32?             // side-to-move cp from latest info line

    override init() {
        super.init()
        Task.detached(priority: .userInitiated) { [weak self] in
            guard let self, let stream = await self.engine.responseStream else { return }
            Task {                                  // start AFTER the stream is subscribed
                await self.engine.start()
                if let big = Bundle.main.url(forResource: "nn-1111cefa1111", withExtension: "nnue") {
                    await self.engine.send(command: .setoption(id: "EvalFile", value: big.path))
                }
                if let small = Bundle.main.url(forResource: "nn-37f18f62d772", withExtension: "nnue") {
                    await self.engine.send(command: .setoption(id: "EvalFileSmall", value: small.path))
                }
                await self.engine.send(command: .isready)
            }
            for await response in stream { self.handle(response) }
        }
    }

    private func handle(_ response: EngineResponse) {
        stateQueue.sync {
            switch response {
            case .readyok:
                if !isReady { isReady = true; readySemaphore.signal() }
            case let .info(info):
                if let score = info.score {
                    if let cp = score.cp {
                        lastRawScoreCp = Int32(cp)
                    } else if let mate = score.mate {
                        lastRawScoreCp = UciEvaluation.shared.mateToCp(matePlies: Int32(mate))
                    }
                }
            case let .bestmove(move, _):
                pendingCompletion?(move)
                pendingCompletion = nil
            default: break
            }
        }
    }

    private func waitUntilReady() -> Bool {
        if stateQueue.sync(execute: { isReady && !isClosed }) { return true }
        _ = readySemaphore.wait(timeout: .now() + Self.readyTimeout)
        readySemaphore.signal() // stay signaled for subsequent waiters
        return stateQueue.sync { isReady && !isClosed }
    }

    /// Sends position+go and blocks until bestmove (or timeout).
    private func runSearch(fen: String, go: EngineCommand) -> String? {
        guard !Thread.isMainThread else { return nil }            // deadlock guard
        requestLock.lock(); defer { requestLock.unlock() }
        guard waitUntilReady() else { return nil }

        let done = DispatchSemaphore(value: 0)
        var bestMove: String?
        stateQueue.sync {
            lastRawScoreCp = nil
            pendingCompletion = { move in bestMove = move; done.signal() }
        }
        Task { [engine] in
            await engine.send(command: .position(.fen(fen)))
            await engine.send(command: go)
        }
        if done.wait(timeout: .now() + Self.responseTimeout) == .timedOut {
            stateQueue.sync { pendingCompletion = nil }
            return nil
        }
        return bestMove
    }

    // MARK: ChessEngine (Kotlin)

    func getBestMove(fen: String) -> String? {
        runSearch(fen: fen, go: .go(movetime: Self.moveTimeMs))
    }

    func evaluate(fen: String) -> KotlinInt? {
        guard runSearch(fen: fen, go: .go(depth: Self.evalDepth)) != nil else { return nil }
        guard let raw = stateQueue.sync(execute: { lastRawScoreCp }) else { return nil }
        let whiteToMove = UciEvaluation.shared.isWhiteToMove(fen: fen)
        return KotlinInt(int: UciEvaluation.shared.toWhitePerspective(scoreCp: raw, whiteToMove: whiteToMove))
    }

    func close() {
        stateQueue.sync { isClosed = true; isReady = false; pendingCompletion = nil }
        Task { [engine] in await engine.stop() }   // idempotent
    }
}
```

Implementer notes: ObjC-exported selector spellings can drift (`getBestMove(fen:)` vs `getBestMoveFen(_:)`) — after the first framework build, check the generated header `app/build/xcode-frameworks/Debug/iphonesimulator*/ChessApp.framework/Headers/ChessApp.h` and let compiler fixits dictate exact signatures, including `Int` vs `Int32` on the `UciEvaluation` helpers. `Score.cp` is `Double?` upstream; truncating to `Int32` is correct (UCI cp values are integral).

**Swift XCTests** — `iosApp/iosAppTests/StockfishChessEngineTests.swift`, hosted on the app (so `Bundle.main` contains the NNUE files); every engine call dispatched to a background queue (the main-thread guard returns nil otherwise):

1. `testBestMoveFromStartPositionIsUciMove` — `getBestMove(startFen)` non-nil, length 4–5 (UCI move). Timeout 60 s.
2. `testEvaluateStartPositionIsRoughlyBalanced` — `evaluate(startFen)` non-nil, `|cp| ≤ 200`.
3. `testCloseIsIdempotentAndSafeBeforeReady` — `close()` twice immediately after init, then `getBestMove` returns nil.

Pattern per test: `let exp = expectation(...)`; `DispatchQueue.global().async { ...asserts...; exp.fulfill() }`; `wait(for: [exp], timeout: 60)`; `defer { engine.close() }`. Use `startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"`.

## Step 7 — iOS Compose UI tests (Kotlin)

New `app/src/iosSimulatorArm64Test/kotlin/com/example/myapplication/GameScreenUiTest.kt` using `runComposeUiTest` (`@OptIn(ExperimentalTestApi::class)`, `kotlin.test.Test`), mirroring the harness style of `GameScreenTest.kt` (`MyApplicationTheme { GameScreen(WindowWidthSizeClass.Medium, viewModel) }` — copy imports from that file):

1. `boardRendersWithPiecesAndControls` — `GameViewModel()`; assert `chess_board`, `board_square_WhitePiece_7_4` (white king e1), `board_square_BlackPiece_0_4` (black king e8) displayed; `offer_draw_button` displayed + enabled. `viewModel.close()` at end.
2. `playerPawnMoveWorks` — no engine (Black answers via `pickMoveCPU`); click `board_square_WhitePiece_6_4` (e2 pawn) then `board_square_PossibleMove_4_4` (e4); `waitUntil(5_000)` for `board_square_WhitePiece_4_4` via `onAllNodesWithTag(...).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()`; assert displayed.
3. `gameOverPopupIsShown` — `GameViewModel(GameUiState(winState = WinState.WHITE))`; assert `winnerText` displayed (`useUnmergedTree = true`).

These run via `:app:iosSimulatorArm64Test` together with the 10 inherited commonTest classes.

**Known risk + mitigation:** `GameScreen` loads piece images/strings via compose resources. If `:app:iosSimulatorArm64Test` fails with a missing-resource error (`MissingResourceException`), don't fight it inline: check the CMP 1.10.x iOS-test-resources status in the JetBrains tracker; if genuinely unsupported, drop these screen-level tests, keep the commonTest suite on the simulator, and rely on Swift XCTests + Android UI tests for UI coverage — note the substitution in the PR. The commonTest suite touches no resources and must pass regardless.

## Step 8 — CI: new `apple` job

Edit `.github/workflows/android-tests.yml`. Leave the existing `instrumented-tests` job byte-for-byte unchanged. Append:

```yaml
  apple:
    name: Build + iOS/macOS checks
    runs-on: macos-latest
    timeout-minutes: 60

    steps:
      - name: Checkout code
        uses: actions/checkout@v5.0.0

      - name: Set up JDK
        uses: actions/setup-java@v5.0.0
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Show Xcode and simulators
        run: |
          xcodebuild -version
          xcrun simctl list devices available | head -30

      - name: Ensure test simulator exists
        run: |
          xcrun simctl list devices available | grep -q "iPhone 16" || \
            xcrun simctl create "iPhone 16" "com.apple.CoreSimulator.SimDeviceType.iPhone-16"

      - name: Kotlin checks (simulator unit+UI tests, desktop tests, device link, DMG)
        run: ./gradlew :app:iosSimulatorArm64Test :app:desktopTest :app:linkReleaseFrameworkIosArm64 :app:packageDistributionForCurrentOS "-PiosSimulatorDeviceId=iPhone 16"

      - name: Build and test iosApp
        run: |
          set -o pipefail
          xcodebuild \
            -project iosApp/iosApp.xcodeproj \
            -scheme iosApp \
            -configuration Debug \
            -destination "platform=iOS Simulator,name=iPhone 16" \
            CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
            test

      - name: Upload test reports
        if: failure()
        uses: actions/upload-artifact@v4.6.2
        with:
          name: apple-test-reports
          path: app/build/reports/tests/
```

Notes: `xcodebuild test` builds the app first (the run-script phase invokes Gradle with `JAVA_HOME` from setup-java) then runs `iosAppTests` on the simulator; `CODE_SIGNING_ALLOWED=NO` is standard for simulator CI. `:app:linkReleaseFrameworkIosArm64` proves the device framework links without a device. If the runner image lacks the `iPhone-16` device type or Swift ≥ 6.0, pick a device from the printed `simctl list` or add `maxim-lobanov/setup-xcode@v1`. The first CI run also verifies the ubuntu job stays green with Apple targets present (KGP auto-disables Apple tasks on Linux); escape hatch: `kotlin.native.ignoreDisabledTargets=true`.

## Step 9 — Documentation

- **README.md**: targets list adds iOS and "Desktop (JVM): Linux and macOS"; setup adds `brew install stockfish` for macOS desktop and an iOS subsection (macOS + Xcode 16+ + JDK 17 required; `open iosApp/iosApp.xcodeproj`, run the `iosApp` scheme; engine bundled, nothing to install); project layout adds `app/src/iosMain` and `iosApp/`; tasks add `./gradlew :app:iosSimulatorArm64Test`.
- **CLAUDE.md**: overview targets; commands (`:app:iosSimulatorArm64Test`, the `xcodebuild ... test` line, second CI job); source-set structure (`iosMain` dependsOn commonMain holding `MainViewController`; `iosSimulatorArm64Test` Compose UI tests; `iosApp/` with XcodeGen `project.yml` as source of truth — regenerate with `xcodegen generate`); engine architecture (Swift `StockfishChessEngine` wrapping ChessKitEngine, async→sync semaphore bridge, NNUE via `setoption EvalFile`/`EvalFileSmall`, injected through `MainViewController(engine:)`); build quirks (static framework `ChessApp`; `embedAndSignAppleFrameworkForXcode` must stay the first build phase with `ENABLE_USER_SCRIPT_SANDBOXING=NO`; simulator device pinned via `iosSimulatorDeviceId` property).
- **docs/Stockfish.md**: iOS section — ChessKitEngine 0.6.0 compiles Stockfish 17 into the app (GPLv3 already covered by `docs/Stockfish-COPYING.txt`); NNUE vendoring rationale + download URLs + integrity rule; note the desktop engine's new path probing.

## Step ordering

1. Step 1 (desktop Dmg + path probing + desktopTest) — verifiable on any host.
2. Steps 2–3 (Gradle iOS targets + MainViewController) — compile check.
3. Step 7 (iOS Kotlin UI tests) — run on simulator.
4. Step 4 (NNUE download/commit).
5. Steps 5–6 (iosApp project, Swift adapter, XCTests) — `xcodegen generate`, build, test.
6. Steps 8–9 (CI + docs), then full verification.

## Verification (run in order)

```bash
# Any host:
./gradlew :app:desktopTest                       # commonTest + new DesktopStockfishEngineTest
./gradlew :androidApp:assembleDebug :app:assembleAndroidDeviceTest :app:check :app:desktopJar :app:packageDistributionForCurrentOS :app:wasmJsBrowserDistribution   # regression: ubuntu CI command still green

# macOS with Xcode 16+ (+ iOS platform: xcodebuild -downloadPlatform iOS):
./gradlew :app:compileKotlinIosArm64 :app:compileKotlinIosSimulatorArm64
./gradlew :app:linkDebugFrameworkIosSimulatorArm64 :app:linkReleaseFrameworkIosArm64
./gradlew :app:iosSimulatorArm64Test "-PiosSimulatorDeviceId=<device from: xcrun simctl list devices available>"
./gradlew :app:packageDistributionForCurrentOS   # → build/compose/binaries/main/dmg/game-1.0.0.dmg
brew install xcodegen && (cd iosApp && xcodegen generate)
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination "platform=iOS Simulator,name=<device>" CODE_SIGNING_ALLOWED=NO test

# Manual smoke: run the iosApp scheme in a simulator; play a few moves; confirm Stockfish is on
# and Black replies in ~1 s. On macOS: brew install stockfish && ./gradlew :app:desktopRun.
```

Expected: `:app:iosSimulatorArm64Test` executes the 10 commonTest classes + `GameScreenUiTest`; `xcodebuild test` runs the 3 XCTests. Note: Kotlin/Native Apple tasks are host-gated — they are skipped on Linux and require a macOS host with Xcode. 

## Risks / implementer notes

1. **ObjC export shapes**: Kotlin `Int?` ⇒ `KotlinInt?`; objects ⇒ `.shared`; top-level functions ⇒ `MainViewControllerKt.*`; protocol selectors may differ from this plan — trust the generated `ChessApp.h` header and compiler fixits. `ChessEngine`/`UciEvaluation`/`GameViewModel` are already `public` — keep them so.
2. **Async→sync bridge**: subscribe to `responseStream` *before* `start()`; never block the main thread or a Swift cooperative-pool thread; always timebox semaphore waits; serialize requests with the lock; `close()` must be safely callable twice (Swift `defer` + Kotlin `GameViewModel.close()` both call it).
3. **NNUE**: option names for Stockfish 17 are exactly `EvalFile` (big) and `EvalFileSmall` (small). Stockfish refuses to search without valid nets, so a wrong filename fails loudly in the first XCTest — enable `loggingEnabled: true` during bring-up.
4. **ChessKitEngine pinning**: 0.6.0 = swift-tools 6.0 (Xcode 16+); 0.7.0 = swift-tools 6.2 (Xcode 26+). Same API either way.
5. **Manual hierarchy**: only the intermediate `iosMain` needs explicit `dependsOn(commonMain.get())`; default source sets (`iosArm64Main`, `iosSimulatorArm64Test`, `desktopTest`) inherit common automatically. Declare targets before `by getting` lookups.
6. **Simulator device pinning**: if `iosSimulatorArm64Test` fails with "device not found", fix the device name (Gradle property / CI `simctl create`), nothing else.
7. **Compose resources in iOS test binaries**: see Step 7 mitigation — must not block the rest of the change.
8. **Don't touch** the Android resource reflection hacks in `app/build.gradle.kts` (~lines 110–122, 136–145) or `androidApp/`; iOS resources flow through the CMP plugin + `embedAndSignAppleFrameworkForXcode`.
9. **Cosmetics**: framework `baseName = "ChessApp"` coexists with the `ChessApp` composable (exported as `ChessAppKt`); Kermit and kotlinx-coroutines both ship iOS artifacts — no dependency changes needed.
10. **Git hygiene**: commit the two `.nnue` files raw (no LFS); gitignore `xcuserdata`; commit `project.yml`, the generated `iosApp.xcodeproj` (incl. shared scheme), and all Swift sources.
