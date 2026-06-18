import Foundation

#if canImport(FoundationModels)
import FoundationModels
#endif

/// Swift facade for Apple Foundation Models (the on-device model behind Apple
/// Intelligence, plan §7). Wraps `LanguageModelSession` with static coach
/// instructions and exposes a narrow async API the Kotlin bridge consumes.
///
/// Availability: Foundation Models requires iOS 26.0+. The app deployment target
/// is iOS 16.0 (set by ChessKitEngine), so all Foundation Models access here is
/// gated by `@available(iOS 26.0, *)`. On older devices the bridge reports
/// unavailable and the orchestrator falls back deterministically.
///
/// Per plan §7.1 the instructions are static (never interpolate user text), and
/// the session is short-lived per call. The 2026 `LanguageModel` protocol
/// direction (provider-swappable across local AFM, Private Cloud Compute, and
/// third-party frontier models) stays a future extension point; the move coach
/// route is LOCAL_ONLY per plan §5.
final class FoundationMoveCoach {

    enum Availability {
        case available
        case unavailable(String)
    }

    static let shared = FoundationMoveCoach()

    private let instructions: String

    private init() {
        self.instructions = """
            You are a chess coach for a casual player.
            Explain only the provided move.
            Do not name openings, engine depth, or ratings unless present in the input.
            Use at most 2 sentences. Do not invent facts.
            """
    }

    func availability() async -> Availability {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            return await checkFoundationModelsAvailability()
        }
        #endif
        return .unavailable("Foundation Models requires iOS 26.0+")
    }

    func warmup() async {
        // Foundation Models sessions are lazy; warmup is a no-op until first call.
    }

    func explain(systemPrompt: String, userPrompt: String, maxTokens: Int) async throws -> String {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            return try await explainWithFoundationModels(
                systemPrompt: systemPrompt,
                userPrompt: userPrompt,
                maxTokens: maxTokens
            )
        }
        #endif
        throw FoundationModelsError.unavailable("Foundation Models requires iOS 26.0+")
    }

    func close() {}

    // MARK: - iOS 26+ implementation

    #if canImport(FoundationModels)
    @available(iOS 26.0, *)
    private func checkFoundationModelsAvailability() async -> Availability {
        let modelAvailability = SystemLanguageModel.default.availability
        switch modelAvailability {
        case .available:
            return .available
        case .unavailable(let reason):
            return .unavailable("unavailable: \(reason)")
        @unknown default:
            return .unavailable("unknown availability state")
        }
    }

    @available(iOS 26.0, *)
    private func explainWithFoundationModels(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int
    ) async throws -> String {
        let session = LanguageModelSession(
            instructions: Instructions(systemPrompt)
        )
        let config = GenerationConfig(
            constraints: nil,
            additionalSessionInstructions: nil,
            maximumOutputTokens: maxTokens
        )
        let response = try await session.respond(
            to: userPrompt,
            generating: String.self,
            options: config
        )
        return response.content
    }
    #endif
}

enum FoundationModelsError: Error {
    case unavailable(String)
    case generationFailed(String)
}
