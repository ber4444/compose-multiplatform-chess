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
        // Register the Foundation Models-backed coach provider before any Kotlin
        // code asks the default iOS factory for one. The bridge checks iOS 26
        // availability per call; pre-iOS-26 devices report unavailable and the
        // orchestrator falls back deterministically (plan §1.4, §7).
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
        }
    }

    init() {
        // Register the Foundation Models-backed coach provider before any Kotlin
        // code asks the default iOS factory for one. The bridge checks iOS 26
        // availability per call; pre-iOS-26 devices report unavailable and the
        // orchestrator falls back deterministically (plan §1.4, §7).
        //
        // Kotlin/Native exposes top-level Kotlin fns as class methods on a
        // generated `*Kt` class — hence the `FoundationModelsBridgeRegistryKt.` /
        // `FoundationModelsBridgeKt.` prefixes (the swift_name annotations keep
        // the call shape unchanged otherwise).
        FoundationModelsBridgeRegistryKt.registerFoundationModelsProvider(provider: {
            FoundationModelsBridgeKt.createFoundationModelsTextGenerator(
                bridge: FoundationMoveCoachBridge()
            )
        })
        FoundationRulesQaBridgeKt.registerFoundationRulesQaProvider(provider: { lookupBridge in
            FoundationRulesQANativeBridge(lookupBridge: lookupBridge)
        })
    }

    var body: some Scene {
        WindowGroup {
            if Self.isBenchmarkMode {
                Color.black.ignoresSafeArea().task {
                    // BENCHMARK_MODE is set, run one cold init iteration and exit
                    do {
                        try await IosBenchRunnerKt.runIosBench(iterations: 1)
                        exit(0)
                    } catch {
                        print("Benchmark failed: \(error)")
                        exit(1)
                    }
                }
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
