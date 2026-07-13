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
        val reason: String,
    ) : OpeningExplainerResult
}

class DefaultOpeningExplainer(
    private val client: OpeningExplainerClient?,
    private val contextProvider: () -> AiContextSnapshot,
) : OpeningExplainer {
    override suspend fun explain(request: OpeningExplainRequest): OpeningExplainerResult {
        return when (val decision = AiRoutePolicyDecider.decide(AiRoutePolicies.openingExplainer, contextProvider())) {
            AiRoutePolicyDecider.Decision.RunCloud -> explainInCloud(request)
            AiRoutePolicyDecider.Decision.RunOnDevice -> fallback(FALLBACK_NO_OPENING_MODEL)
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
            } ?: return fallback(AiRoutePolicyDecider.FALLBACK_TIMEOUT)
            OpeningExplainerResult.Success(response, AiRoute.Cloud)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            fallback(FALLBACK_CLOUD_ERROR)
        }
    }

    private fun fallback(reason: String) = OpeningExplainerResult.Fallback(
        response = OpeningExplainResponse(
            text = "The opening explanation is unavailable offline. Focus on central control, develop minor pieces, and secure the king.",
            passages = emptyList(),
            composerId = "offline-fallback",
        ),
        reason = reason,
    )

    companion object {
        const val FALLBACK_CLOUD_ERROR = "cloud opening service unavailable"
        const val FALLBACK_NO_OPENING_MODEL = "no on-device opening explainer"
    }
}
