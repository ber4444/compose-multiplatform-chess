package com.example.ondeviceai

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

class DefaultGameSummaryOrchestrator(
    private val executor: AiRouteExecutor,
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
            is AiRoutePolicyDecider.Decision.RunOnDevice -> emit(runOnDevice(request, decision.route))
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
            return fallback(
                request,
                AiRoutePolicyDecider.FallbackReason.Other("generator factory failed: ${it.message}"),
            )
        } ?: return fallback(request, AiRoutePolicyDecider.FallbackReason.NoLocalModel)

        return try {
            when (val status = generator.status()) {
                is AiAvailability.Available -> runGeneration(request, generator, start)
                is AiAvailability.Busy -> fallback(request, AiRoutePolicyDecider.FallbackReason.Quota)
                is AiAvailability.Error ->
                    fallback(
                        request,
                        AiRoutePolicyDecider.FallbackReason.Other("availability error: ${status.message}"),
                    )
                is AiAvailability.Downloadable,
                is AiAvailability.Downloading,
                AiAvailability.Unavailable ->
                    fallback(request, AiRoutePolicyDecider.FallbackReason.NoLocalModel)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            logger.w(t) { "On-device generation threw; falling back" }
            fallback(request, AiRoutePolicyDecider.FallbackReason.Other("generation error: ${t.message}"))
        } finally {
            runCatching { generator.release() }
        }
    }

    private suspend fun runGeneration(
        request: GameSummaryRequest,
        generator: OnDeviceTextGenerator,
        startMs: Long,
    ): GameSummaryEvent {
        val prompt = GameSummaryPromptBuilder.build(request)
        // "Timed out" and "the generator finished but never emitted a Final event" are different
        // failures and must not share a reason: FallbackPresentation maps Timeout to *Retryable*, so
        // reporting a missing Final as a timeout hands the user a Retry button for a condition retry
        // cannot fix. A generator that stops emitting mid-flow — AntiRepetitionGuard does exactly
        // this when it trips — is a protocol fault, and Silent is the honest state for it.
        val outcome = when (val result = collectGenerate(request, generator, prompt, startMs)) {
            is CollectResult.TimedOut -> return fallback(request, AiRoutePolicyDecider.FallbackReason.Timeout)
            is CollectResult.NoFinalEvent -> return fallback(
                request,
                AiRoutePolicyDecider.FallbackReason.Other("generator emitted no final event"),
            )
            is CollectResult.Completed -> result.outcome
        }

        // For the summary, we don't have a complex validation step like MoveCoach response validation.
        // As long as we got text, we accept it.
        if (outcome.rawText.isBlank()) {
            return fallback(request, AiRoutePolicyDecider.FallbackReason.Validation)
        }

        return success(outcome.rawText, outcome.metrics)
    }

    private suspend fun collectGenerate(
        request: GameSummaryRequest,
        generator: OnDeviceTextGenerator,
        prompt: AiGenerationRequest,
        startMs: Long,
    ): CollectResult {
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
                    is AiTokenOrFinal.ToolCall -> {}
                }
            }
            true
        }

        if (completed == null) return CollectResult.TimedOut
        val observed = metrics ?: return CollectResult.NoFinalEvent

        return CollectResult.Completed(
            GenerationOutcome(buffer.toString().trim(), observed.copy(completeMs = clock() - startMs)),
        )
    }

    /**
     * Why this is a sealed type and not a nullable [GenerationOutcome]: the null carried two
     * distinct failures that map to different product states, and the `?: AiInferenceMetrics(...)`
     * default that used to sit below the null check was unreachable — it read as a safety net and
     * was not one.
     */
    private sealed interface CollectResult {
        data object TimedOut : CollectResult
        data object NoFinalEvent : CollectResult
        data class Completed(val outcome: GenerationOutcome) : CollectResult
    }

    /**
     * Every give-up path lands here, and it now composes the turning points rather than apologising.
     *
     * The old text — "No summary available. Review the PGN to spot your mistakes!" — was returned
     * while holding the player's three worst moves, each already a finished sentence, because that
     * list was only ever used as prompt input. See [GameSummaryGrounding].
     */
    private fun fallback(request: GameSummaryRequest, reason: AiRoutePolicyDecider.FallbackReason): GameSummaryEvent.Complete {
        val turningPoints = GameSummaryPromptBuilder.extractTurningPoints(
            request.moveHistory,
            request.playerSide,
            request.engineDifficultyName,
        )
        return complete(GameSummaryResult.FellBack(GameSummaryGrounding.compose(turningPoints), reason))
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
                availableLocalVendors = emptyList(),
                isAppForegrounded = true,
                userSetting = AiUserSetting.OFFLINE_ONLY,
            )
        }
    }
}
