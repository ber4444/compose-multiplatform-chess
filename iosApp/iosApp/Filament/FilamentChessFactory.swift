// Issue #54 — Swift factory injected into the Kotlin entry point for the iOS Filament backend.
//
// Conforms to the Kotlin-exported `FilamentChessViewFactory` protocol. Mirrors the
// StockfishChessEngine injection pattern: Swift owns native dependencies while shared Kotlin owns
// app state and renderer orchestration.

import ChessApp   // Kotlin framework: FilamentChessViewFactory / FilamentChessNativeView protocols

final class FilamentChessFactory: NSObject, FilamentChessViewFactory {
    func create() -> FilamentChessNativeView {
        FilamentChessView()
    }
}
