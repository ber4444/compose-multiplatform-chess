import SwiftUI

@main
struct iOSApp: App {
    // True when the app is launched as an XCTest host (Xcode sets this env var for hosted unit tests).
    private static let isRunningTests =
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil

    var body: some Scene {
        WindowGroup {
            if Self.isRunningTests {
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
