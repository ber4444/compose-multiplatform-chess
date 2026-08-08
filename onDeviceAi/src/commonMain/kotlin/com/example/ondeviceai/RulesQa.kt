package com.example.ondeviceai

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class RulesQaModelOutput(
    val text: String,
    val retrievedPassageIds: List<String>,
)

fun interface RulesQaAnswerer {
    suspend fun answer(question: String, route: VendorRoute): RulesQaModelOutput
}

expect fun defaultRulesQaAnswerer(lookupTool: RuleLookupTool): RulesQaAnswerer?

sealed interface RulesQaResult {
    data class Success(
        val text: String,
        val passageIds: List<String>,
    ) : RulesQaResult

    data class FellBack(
        val text: String,
        val reason: AiRoutePolicyDecider.FallbackReason,
    ) : RulesQaResult
}

object RulesQaFallback {
    const val TEXT = "I couldn't verify that rule from the offline reference. A legal move must " +
        "leave your king safe; checkmate ends the game, while stalemate is a draw."
}

/**
 * The retrieved passage, as a finished answer.
 *
 * Rules Q&A is a *retrieval* feature: BM25 over the bundled corpus finds the rule deterministically,
 * and the model's only job is to phrase what was found. Those two steps fail independently, and the
 * phrasing step fails often — the Android runtime is a ~270M model, which frequently will not
 * reproduce an exact `[passage-id]` no matter how the prompt asks. Before this existed, that
 * phrasing failure discarded a *correct retrieval* and emitted [RulesQaFallback.TEXT], which tells a
 * user whose rule was found, and is sitting in memory, that it could not be found.
 *
 * So the retrieved passage is the floor. Mirrors `DeterministicCoach` on the move-coach side and the
 * house rule it encodes: code retrieves, the model only narrates. A narration failure costs the
 * user some fluency, never the answer.
 */
object RulesQaGrounding {

    /** The top passage rendered as a cited answer, trimmed to the validator's budget. */
    fun composeFromPassages(passages: List<RulePassage>): String {
        val top = passages.firstOrNull() ?: return ""
        val citation = " [${top.id}]"
        val budget = RulesQaResponseValidator.MAX_OUTPUT_CHARS - citation.length
        val body = "${top.title}: ${top.text}"
        if (budget <= 1) return citation.trim()
        return if (body.length <= budget) body + citation
        else body.take(budget - 1).trimEnd() + "…" + citation
    }

    /**
     * The model's wording when it survives [RulesQaResponseValidator], the passage itself otherwise.
     *
     * Deliberately re-uses the orchestrator's validator rather than a looser check of its own: an
     * answer that would be rejected downstream must not be preferred here, or the two disagree and
     * the user gets the fallback anyway.
     */
    fun answerOrReference(modelText: String, passages: List<RulePassage>): String =
        when (RulesQaResponseValidator.validate(modelText, passages.map { it.id })) {
            is RulesQaResponseValidator.Result.Valid -> modelText.trim()
            is RulesQaResponseValidator.Result.Invalid -> composeFromPassages(passages)
        }
}

object RulesQaResponseValidator {
    const val MAX_OUTPUT_CHARS = 600

    fun validate(text: String, retrievedPassageIds: List<String>): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.Invalid("empty answer")
        if (trimmed.length > MAX_OUTPUT_CHARS) return Result.Invalid("answer too long")
        if (retrievedPassageIds.isEmpty()) return Result.Invalid("no passage was retrieved")
        val cited = retrievedPassageIds.filter { id -> trimmed.contains("[$id]") }
        if (cited.isEmpty()) return Result.Invalid("answer does not cite a retrieved passage id")
        return Result.Valid(trimmed, cited.distinct())
    }

    sealed interface Result {
        data class Valid(val text: String, val citedPassageIds: List<String>) : Result
        /** A diagnostic naming the broken rule; the caller maps every rejection to
         *  [AiRoutePolicyDecider.FallbackReason.Validation]. Mirrors [MoveCoachResponseValidator]. */
        data class Invalid(val reason: String) : Result
    }
}

class DefaultRulesQaOrchestrator(
    private val answerer: RulesQaAnswerer,
    private val contextProvider: suspend () -> AiContextSnapshot,
) {
    // Not a constructor parameter: contextProvider is passed as a trailing lambda at every call
    // site, and this module is published, so appending a param is both a source and a binary break.
    private val logger: Logger = Logger.withTag("RulesQa")

    suspend fun answer(question: String): RulesQaResult {
        val normalizedQuestion = question.trim()
        if (normalizedQuestion.isEmpty()) {
            return fallback(AiRoutePolicyDecider.FallbackReason.Other("empty question"))
        }

        val context = try {
            contextProvider()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return fallback(
                AiRoutePolicyDecider.FallbackReason.Other("context snapshot failed: ${t.message}"),
            )
        }

        return when (val decision = AiRoutePolicyDecider.decide(AiRoutePolicies.rulesQaOffline, context)) {
            is AiRoutePolicyDecider.Decision.RunOnDevice -> runOnDevice(normalizedQuestion, decision.route)
            is AiRoutePolicyDecider.Decision.RunCloud ->
                fallback(AiRoutePolicyDecider.FallbackReason.Other("cloud route not supported"))
            is AiRoutePolicyDecider.Decision.FallBack -> fallback(decision.reason)
        }
    }

    private suspend fun runOnDevice(question: String, route: VendorRoute): RulesQaResult {
        val output = try {
            withTimeoutOrNull(AiRoutePolicies.rulesQaOffline.latencyBudget.completeMs) {
                answerer.answer(question, route)
            } ?: return fallback(AiRoutePolicyDecider.FallbackReason.Timeout)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return fallback(
                AiRoutePolicyDecider.FallbackReason.Other("rules generation failed: ${t.message}"),
            )
        }

        return when (
            val validation = RulesQaResponseValidator.validate(
                output.text,
                output.retrievedPassageIds,
            )
        ) {
            is RulesQaResponseValidator.Result.Valid -> RulesQaResult.Success(
                text = validation.text,
                passageIds = validation.citedPassageIds,
            )
            // The specific broken rule is a diagnostic; the product state is the same either way.
            // Mirrors DefaultAiCoachOrchestrator: log the detail, fall back with Validation.
            is RulesQaResponseValidator.Result.Invalid -> {
                logger.w { "Rules Q&A validation failed: ${validation.reason}" }
                fallback(AiRoutePolicyDecider.FallbackReason.Validation)
            }
        }
    }

    private fun fallback(reason: AiRoutePolicyDecider.FallbackReason) = RulesQaResult.FellBack(
        text = RulesQaFallback.TEXT,
        reason = reason,
    )
}
