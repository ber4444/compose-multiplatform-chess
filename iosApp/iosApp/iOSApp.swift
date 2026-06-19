import SwiftUI
import ChessApp  // OnDeviceAi symbols are re-exported into ChessApp via
                 // `export(project(":onDeviceAi"))` in app/build.gradle.kts
                 // (KT-42254: single Kotlin/Native runtime in the binary).

@main
struct iOSApp: App {
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
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
