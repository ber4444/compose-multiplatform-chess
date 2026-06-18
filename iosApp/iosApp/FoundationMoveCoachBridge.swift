import Foundation
import ChessApp  // Kotlin framework (FoundationModelsBridge protocol, AiAvailability types)

/// Adopts the Kotlin `FoundationModelsBridge` protocol so the common Kotlin
/// orchestrator can drive Foundation Models without ever touching Swift types.
///
/// Registered with `FoundationModelsBridgeRegistry` at app startup (iOSApp.swift)
/// so the iOS default factory in `OnDeviceTextGeneratorFactory.ios.kt` picks it up.
///
/// The Kotlin `suspend` protocol methods are surfaced to Swift as `completionHandler:`
/// callbacks (Kotlin/Native interop convention — same shape as `StockfishChessEngine`
/// adopts `ChessEngine`). Each callback delegates to `FoundationMoveCoach.shared`.
final class FoundationMoveCoachBridge: NSObject, FoundationModelsBridge {

    private let coach = FoundationMoveCoach.shared

    // MARK: - FoundationModelsBridge (Kotlin protocol)

    func status(completionHandler: @escaping (AiAvailability?, Error?) -> Void) {
        Task { [coach] in
            let availability = await coach.availability()
            completionHandler(self.toKotlin(availability), nil)
        }
    }

    func warmup(completionHandler: @escaping (Error?) -> Void) {
        Task { [coach] in
            await coach.warmup()
            completionHandler(nil)
        }
    }

    func generate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int32,
        completionHandler: @escaping (String?, Error?) -> Void
    ) {
        Task { [coach] in
            do {
                let text = try await coach.explain(
                    systemPrompt: systemPrompt,
                    userPrompt: userPrompt,
                    maxTokens: Int(maxTokens)
                )
                completionHandler(text, nil)
            } catch {
                completionHandler(nil, error)
            }
        }
    }

    func close() {
        coach.close()
    }

    // MARK: - Swift → Kotlin mapping

    private func toKotlin(_ availability: FoundationMoveCoach.Availability) -> AiAvailability {
        switch availability {
        case .available:
            return AiAvailabilityAvailable()
        case .unavailable(let reason):
            return AiAvailabilityError(message: reason)
        }
    }
}
