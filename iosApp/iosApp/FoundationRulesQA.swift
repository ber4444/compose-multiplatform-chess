import Foundation
import ChessApp
import NaturalLanguage

#if canImport(FoundationModels)
import FoundationModels
#endif

struct FoundationRulesQANativeOutput {
    let text: String
    let passageIDs: [String]
}

/// iOS rules Q&A backed by a real Foundation Models `Tool` invocation.
/// The model chooses the lookup query mid-response; the tool calls the shared Kotlin offline index.
final class FoundationRulesQA {

    func answer(
        question: String,
        lookupBridge: FoundationRuleLookupBridge
    ) async throws -> FoundationRulesQANativeOutput {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            return try await answerWithFoundationModels(
                question: question,
                lookupBridge: lookupBridge
            )
        }
        #endif
        throw FoundationModelsError.unavailable("Foundation Models requires iOS 26.0+")
    }

    #if canImport(FoundationModels)
    @available(iOS 26.0, *)
    private func answerWithFoundationModels(
        question: String,
        lookupBridge: FoundationRuleLookupBridge
    ) async throws -> FoundationRulesQANativeOutput {
        let citations = RuleCitationCollector()
        let lookupTool = FoundationRuleLookupTool(
            lookupBridge: lookupBridge,
            citations: citations
        )
        let session = LanguageModelSession(
            model: .default,
            tools: [lookupTool],
            instructions: """
                You answer chess-rules questions from the lookup_rule tool only.
                Always call lookup_rule before answering. Use only its returned passages.
                Cite at least one exact passage id in square brackets, for example [stalemate].
                If lookup returns no passage, say that the offline reference could not verify it.
                """
        )
        let response = try await session.respond(
            to: question,
            options: GenerationOptions(temperature: 0.2, maximumResponseTokens: 180)
        )
        return FoundationRulesQANativeOutput(
            text: response.content,
            passageIDs: await citations.allIDs()
        )
    }
    #endif
}

#if canImport(FoundationModels)
@available(iOS 26.0, *)
@Generable(description: "A concise search query for the offline chess-rules corpus")
private struct RuleLookupArguments {
    @Guide(description: "The rule or situation to look up")
    var query: String
}

@available(iOS 26.0, *)
private actor RuleCitationCollector {
    private var passageIDs = Swift.Set<String>()

    func record(_ ids: [String]) {
        passageIDs.formUnion(ids)
    }

    func allIDs() -> [String] {
        passageIDs.sorted()
    }
}

@available(iOS 26.0, *)
private final class FoundationRuleLookupTool: Tool, @unchecked Sendable {
    typealias Arguments = RuleLookupArguments
    typealias Output = String

    let name = "lookup_rule"
    let description = "Searches the bundled offline chess-rules corpus for relevant passages."

    private let lookupBridge: FoundationRuleLookupBridge
    private let citations: RuleCitationCollector

    init(
        lookupBridge: FoundationRuleLookupBridge,
        citations: RuleCitationCollector
    ) {
        self.lookupBridge = lookupBridge
        self.citations = citations
    }

    func call(arguments: RuleLookupArguments) async throws -> String {
        let payload: String
        if let ranked = try await embeddingRankedPayload(query: arguments.query) {
            payload = ranked
        } else {
            payload = try await lookupFallback(query: arguments.query)
        }
        let ids = payload.split(separator: "\n").compactMap { line -> String? in
            let fields = line.split(separator: "\t", maxSplits: 2, omittingEmptySubsequences: false)
            return fields.count == 3 ? String(fields[0]) : nil
        }
        await citations.record(ids)
        return payload.isEmpty ? "No matching offline rule passage." : payload
    }

    /// Uses Apple's on-device sentence embedding at query time. The shared Kotlin BM25 lookup is
    /// retained as an offline fallback for languages/devices where NLEmbedding is unavailable.
    private func embeddingRankedPayload(query: String) async throws -> String? {
        guard let embedding = NLEmbedding.sentenceEmbedding(for: .english) else { return nil }
        let corpus = try await corpusPayload()
        let passages = corpus.split(separator: "\n").compactMap { line -> RuleCorpusRow? in
            let fields = line.split(separator: "\t", maxSplits: 2, omittingEmptySubsequences: false)
            guard fields.count == 3 else { return nil }
            return RuleCorpusRow(
                id: String(fields[0]),
                title: String(fields[1]),
                text: String(fields[2])
            )
        }
        guard !passages.isEmpty else { return nil }
        let ranked = passages.map { passage in
            let searchableText = "\(passage.title). \(passage.text)"
            let distance = embedding.distance(
                between: query,
                and: searchableText,
                distanceType: .cosine
            )
            return (passage, distance)
        }.sorted { left, right in
            if left.1 == right.1 { return left.0.id < right.0.id }
            return left.1 < right.1
        }.prefix(4)
        return ranked.map { passage, _ in
            "\(passage.id)\t\(passage.title)\t\(passage.text)"
        }.joined(separator: "\n")
    }

    private func corpusPayload() async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            lookupBridge.corpusForEmbedding { result, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: result ?? "")
                }
            }
        }
    }

    private func lookupFallback(query: String) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            lookupBridge.lookupForTool(query: query) { result, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: result ?? "")
                }
            }
        }
    }
}

@available(iOS 26.0, *)
private struct RuleCorpusRow {
    let id: String
    let title: String
    let text: String
}
#endif
