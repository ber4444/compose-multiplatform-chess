package com.example.ondeviceai

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

class DefaultGameSummaryOrchestrator(
    private val executor: VendorRouteExecutor,
    private val contextProvider: suspend () -> AiContextSnapshot = DefaultContextProvider,
    private val clock: () -> Long = ::defaultNowMs,
    private val logger: Logger = Logger.withTag("GameSummary"),
) : GameSummaryOrchestrator {

    override fun summarizeGameStreaming(request: GameSummaryRequest): Flow<GameSummaryEvent> = flow {
        val context = runCatching { contextProvider() }.getOrElse {
            emit(complete(GameSummaryResult.Failed("context snapshot failed: ${it.message}")))
            return@flow
        }
        val decision = AiRoutePolicyDecider.decide(request.policy, context)
        when (decision) {
            is AiRoutePolicyDecider.Decision.Route -> emit(runOnDevice(request, decision.route))
            is AiRoutePolicyDecider.Decision.RunCloud -> emit(complete(GameSummaryResult.Failed("Cloud route not supported in onDeviceAi orchestrator")))
            is AiRoutePolicyDecider.Decision.FallBack ->
                emit(fallback(request, decision.reason))
        }
    }

    override suspend fun summarizeGame(request: GameSummaryRequest): GameSummaryResult {
        var result: GameSummaryResult = GameSummaryResult.Failed("orchestrator produced no result")
        summarizeGameStreaming(request).collect { event ->
            if (event is GameSummaryEvent.Complete) result = event.result
        }
        return result
    }

    private suspend fun runOnDevice(request: GameSummaryRequest, route: VendorRoute): GameSummaryEvent {
        val start = clock()
        val generator = runCatching { executor.execute(route) }.getOrElse {
            return fallback(request, "generator factory failed: ${it.message}")
        } ?: return fallback(request, AiRoutePolicyDecider.FALLBACK_NO_LOCAL_MODEL)

        return try {
            when (val status = generator.status()) {
                is AiAvailability.Available -> runGeneration(request, generator, start)
                is AiAvailability.Busy -> fallback(request, AiRoutePolicyDecider.FALLBACK_QUOTA)
                is AiAvailability.Error ->
                    fallback(request, "availability error: ${status.message}")
                is AiAvailability.Downloadable,
                is AiAvailability.Downloading,
                AiAvailability.Unavailable ->
                    fallback(request, AiRoutePolicyDecider.FALLBACK_NO_LOCAL_MODEL)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            logger.w(t) { "On-device generation threw; falling back" }
            fallback(request, "generation error: ${t.message}")
        } finally {
            runCatching { generator.close() }
        }
    }

    private suspend fun runGeneration(
        request: GameSummaryRequest,
        generator: OnDeviceTextGenerator,
        startMs: Long,
    ): GameSummaryEvent {
        val prompt = GameSummaryPromptBuilder.build(request)
        val outcome = collectGenerate(request, generator, prompt, startMs)
            ?: return fallback(request, AiRoutePolicyDecider.FALLBACK_TIMEOUT)

        // For the summary, we don't have a complex validation step like MoveCoach response validation.
        // As long as we got text, we accept it.
        if (outcome.rawText.isBlank()) {
            return fallback(request, AiRoutePolicyDecider.FALLBACK_VALIDATION)
        }

        return success(outcome.rawText, outcome.metrics)
    }

    private suspend fun collectGenerate(
        request: GameSummaryRequest,
        generator: OnDeviceTextGenerator,
        prompt: AiGenerationRequest,
        startMs: Long,
    ): GenerationOutcome? {
        val buffer = StringBuilder()
        var metrics: AiInferenceMetrics? = null

        val flow = generator.generate(prompt)
        val completed = withTimeoutOrNull(45_000L) { // Allow up to 45 seconds for a full PGN process and generation
            flow.collect { token ->
                when (token) {
                    is AiTokenOrFinal.Token -> {
                        buffer.append(token.text)
                    }
                    is AiTokenOrFinal.Final -> {
                        buffer.append(token.text)
                        metrics = token.metrics.copy(
                            firstTokenMs = token.metrics.firstTokenMs ?: (clock() - startMs)
                        )
                    }
                }
            }
            true
        }

        if (completed == null || metrics == null) return null

        val finalMetrics = metrics?.copy(completeMs = clock() - startMs)
            ?: AiInferenceMetrics(
                firstTokenMs = null,
                completeMs = clock() - startMs,
                tokenCount = 0,
                route = AiRoute.OnDevice,
            )

        return GenerationOutcome(buffer.toString().trim(), finalMetrics)
    }

    private fun fallback(request: GameSummaryRequest, reason: String): GameSummaryEvent.Complete {
        val fallbackText = "No summary available. Review the PGN to spot your mistakes!"
        return complete(GameSummaryResult.FellBack(fallbackText, reason))
    }

    private fun success(
        text: String,
        metrics: AiInferenceMetrics
    ): GameSummaryEvent.Complete {
        return complete(
            GameSummaryResult.Success(
                GameSummaryExplanation(
                    explanation = text,
                    route = AiRoute.OnDevice,
                    metrics = metrics,
                )
            )
        )
    }

    private fun complete(result: GameSummaryResult) = GameSummaryEvent.Complete(result)

    private data class GenerationOutcome(
        val rawText: String,
        val metrics: AiInferenceMetrics,
    )

    private companion object {
        val DefaultContextProvider: suspend () -> AiContextSnapshot = {
            AiContextSnapshot(
                isDeviceModelAvailable = false,
                isAppForegrounded = true,
                userSetting = AiUserSetting.OFFLINE_ONLY,
            )
        }
    }
}
