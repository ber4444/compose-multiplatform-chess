package com.example.ondeviceai

import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.OpeningExplainResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

fun interface OpeningExplainerClient {
    suspend fun explain(request: OpeningExplainRequest): OpeningExplainResponse

    fun close() = Unit
}

fun interface OpeningExplainer {
    suspend fun explain(request: OpeningExplainRequest): OpeningExplainerResult

    fun close() = Unit
}

sealed interface OpeningExplainerResult {
    data class Success(
        val response: OpeningExplainResponse,
        val route: AiRoute,
    ) : OpeningExplainerResult

    data class Fallback(
        val response: OpeningExplainResponse,
        val reason: AiRoutePolicyDecider.FallbackReason,
    ) : OpeningExplainerResult {
        /** Provenance (B11): derived, not a parameter — a caller cannot hand this a route that
         *  contradicts [reason], and the field can't go stale when [reason] changes. */
        val route: AiRoute get() = AiRoute.Fallback(reason)
    }
}

class DefaultOpeningExplainer(
    private val client: OpeningExplainerClient?,
    private val contextProvider: () -> AiContextSnapshot,
) : OpeningExplainer {
    override suspend fun explain(request: OpeningExplainRequest): OpeningExplainerResult {
        return when (val decision = AiRoutePolicyDecider.decide(AiRoutePolicies.openingExplainer, contextProvider())) {
            AiRoutePolicyDecider.Decision.RunCloud -> explainInCloud(request)
            is AiRoutePolicyDecider.Decision.RunOnDevice -> fallback(FALLBACK_NO_OPENING_MODEL)
            is AiRoutePolicyDecider.Decision.FallBack -> fallback(decision.reason)
        }
    }

    override fun close() {
        client?.close()
    }

    private suspend fun explainInCloud(request: OpeningExplainRequest): OpeningExplainerResult {
        val availableClient = client ?: return fallback(FALLBACK_CLOUD_ERROR)
        return try {
            val response = withTimeoutOrNull(
                AiRoutePolicies.openingExplainer.latencyBudget.completeMs,
            ) {
                availableClient.explain(request)
            } ?: return fallback(AiRoutePolicyDecider.FallbackReason.Timeout)
            OpeningExplainerResult.Success(response, AiRoute.Cloud)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            fallback(FALLBACK_CLOUD_ERROR)
        }
    }

    private fun fallback(reason: AiRoutePolicyDecider.FallbackReason) = OpeningExplainerResult.Fallback(
        response = OpeningExplainResponse(
            text = "The opening explanation is unavailable offline. Focus on central control, develop minor pieces, and secure the king.",
            passages = emptyList(),
            composerId = "offline-fallback",
        ),
        reason = reason,
    )

    companion object {
        // Surface-specific reasons that no shared FallbackReason case covers. They stay distinct
        // values (not bare NoRoute) so the log line names which cloud surface failed.
        val FALLBACK_CLOUD_ERROR: AiRoutePolicyDecider.FallbackReason =
            AiRoutePolicyDecider.FallbackReason.Other("cloud opening service unavailable")
        val FALLBACK_NO_OPENING_MODEL: AiRoutePolicyDecider.FallbackReason =
            AiRoutePolicyDecider.FallbackReason.Other("no on-device opening explainer")
    }
}
