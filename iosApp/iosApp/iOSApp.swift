import SwiftUI
import ChessApp  // OnDeviceAi symbols are re-exported into ChessApp via
                 // `export(project(":onDeviceAi"))` in app/build.gradle.kts
                 // (KT-42254: single Kotlin/Native runtime in the binary).

@main
struct iOSApp: App {
    // True when the app is launched as an XCTest host (Xcode sets this env var for hosted unit tests).
    private static let isRunningTests =
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
        
    private static let isBenchmarkMode =
        ProcessInfo.processInfo.environment["BENCHMARK_MODE"] != nil

    init() {
        // Register the Foundation Models-backed coach + rules-QA providers before any Kotlin
        // code asks the default iOS factory for one. The bridges check iOS 26 availability per
        // call; pre-iOS-26 devices report unavailable and the orchestrators fall back
        // deterministically (plan §1.4, §7).
        //
        // Kotlin/Native exposes top-level Kotlin fns as class methods on a
        // generated `*Kt` class — hence the `FoundationModelsBridgeRegistryKt.` /
        // `FoundationModelsBridgeKt.` prefixes (the swift_name annotations keep
        // the call shape unchanged otherwise).
        if !Self.isRunningTests {
            FoundationModelsBridgeRegistryKt.registerFoundationModelsProvider(provider: {
                FoundationModelsBridgeKt.createFoundationModelsTextGenerator(
                    bridge: FoundationMoveCoachBridge()
                )
            })
            FoundationRulesQaBridgeKt.registerFoundationRulesQaProvider(provider: { lookupBridge in
                FoundationRulesQANativeBridge(lookupBridge: lookupBridge)
            })
        }
    }

    var body: some Scene {
        WindowGroup {
            if Self.isBenchmarkMode {
                BenchmarkView()
            } else if Self.isRunningTests {
                // Under XCTest, don't spin up the Compose (Skia-Metal) + Filament UI. The GPU-limited
                // CI simulator's Metal host (SimMetalHost) crashes while rendering the first frame of
                // the full app, which aborted the test host before tests could connect. The Swift unit
                // tests (StockfishChessEngineTests) drive the engine directly and don't need the UI.
                Color.black.ignoresSafeArea()
            } else {
                ContentView()
            }
        }
    }
}

struct BenchmarkView: View {
    @State private var status = "Running Benchmark..."
    
    var body: some View {
        Color.black.ignoresSafeArea().overlay(
            Text(status)
                .foregroundColor(.white)
        ).task {
            do {
                // BENCHMARK_SUITE=summary measures Game Summary instead of the Move Coach. They are
                // different surfaces with different budgets — a wait that disqualifies the coach
                // may be fine behind a button at game end — so they are never averaged together.
                if ProcessInfo.processInfo.environment["BENCHMARK_SUITE"] == "summary" {
                    try await IosSummaryBenchKt.runIosSummaryBench(iterations: 1)
                } else {
                    // The same engine the app plays with, so the bench can assess each golden
                    // position and hand the model real facts — without it every row is
                    // `factsPopulated:false` and measures the harness rather than Foundation Models.
                    try await IosBenchRunnerKt.runIosBench(engine: StockfishChessEngine(), iterations: 1)
                }
                status = "Benchmark Complete"
            } catch {
                print("Benchmark failed: \(error)")
                status = "Benchmark Failed"
            }
        }
    }
}
