import Foundation
import ChessApp

/// Kotlin bridge for the native Foundation Models rules session. The shared validator remains the
/// final authority: answers without an id recorded by the tool invocation fall back statically.
final class FoundationRulesQANativeBridge: NSObject, FoundationRulesQaBridge {

    private let rulesQA = FoundationRulesQA()
    private let lookupBridge: FoundationRuleLookupBridge

    init(lookupBridge: FoundationRuleLookupBridge) {
        self.lookupBridge = lookupBridge
    }

    func answer(
        question: String,
        completionHandler: @escaping (FoundationRulesQaOutput?, Error?) -> Void
    ) {
        Task { [rulesQA, lookupBridge] in
            do {
                let output = try await rulesQA.answer(
                    question: question,
                    lookupBridge: lookupBridge
                )
                completionHandler(
                    FoundationRulesQaOutput(
                        text: output.text,
                        passageIdsCsv: output.passageIDs.joined(separator: ",")
                    ),
                    nil
                )
            } catch {
                completionHandler(nil, error)
            }
        }
    }
}
