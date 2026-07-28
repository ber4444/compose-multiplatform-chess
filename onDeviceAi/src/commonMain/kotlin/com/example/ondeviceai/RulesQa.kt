package com.example.ondeviceai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class RulesQaModelOutput(
    val text: String,
    val retrievedPassageIds: List<String>,
)

fun interface RulesQaAnswerer {
    suspend fun answer(question: String): RulesQaModelOutput
}

expect fun defaultRulesQaAnswerer(lookupTool: RuleLookupTool): RulesQaAnswerer?

sealed interface RulesQaResult {
    data class Success(
        val text: String,
        val passageIds: List<String>,
    ) : RulesQaResult

    data class FellBack(
        val text: String,
        val reason: String,
    ) : RulesQaResult
}

object RulesQaFallback {
    const val TEXT = "I couldn't verify that rule from the offline reference. A legal move must " +
        "leave your king safe; checkmate ends the game, while stalemate is a draw."
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
        data class Invalid(val reason: String) : Result
    }
}

class DefaultRulesQaOrchestrator(
    private val answerer: RulesQaAnswerer,
    private val contextProvider: suspend () -> AiContextSnapshot,
) {
    suspend fun answer(question: String): RulesQaResult {
        val normalizedQuestion = question.trim()
        if (normalizedQuestion.isEmpty()) return fallback("empty question")

        val context = try {
            contextProvider()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return fallback("context snapshot failed: ${t.message}")
        }

        return when (val decision = AiRoutePolicyDecider.decide(AiRoutePolicies.rulesQaOffline, context)) {
            is AiRoutePolicyDecider.Decision.Route -> runOnDevice(normalizedQuestion)
            is AiRoutePolicyDecider.Decision.FallBack -> fallback(decision.reason)
        }
    }

    private suspend fun runOnDevice(question: String): RulesQaResult {
        val output = try {
            withTimeoutOrNull(AiRoutePolicies.rulesQaOffline.latencyBudget.completeMs) {
                answerer.answer(question)
            } ?: return fallback(AiRoutePolicyDecider.FALLBACK_TIMEOUT)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return fallback("rules generation failed: ${t.message}")
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
            is RulesQaResponseValidator.Result.Invalid -> fallback(
                "${AiRoutePolicyDecider.FALLBACK_VALIDATION}: ${validation.reason}",
            )
        }
    }

    private fun fallback(reason: String) = RulesQaResult.FellBack(
        text = RulesQaFallback.TEXT,
        reason = reason,
    )
}
