package com.example.ondeviceai

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import com.example.ondeviceai.bench.BenchProbe
import com.example.ondeviceai.bench.NoOpBenchProbe
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

sealed interface MoveCoachEvent {
    data class Streaming(val partialText: String) : MoveCoachEvent
    data class Complete(val result: MoveCoachResult) : MoveCoachEvent
}

class DefaultAiCoachOrchestrator(
    private val executor: AiRouteExecutor,
    private val contextProvider: suspend () -> AiContextSnapshot = DefaultContextProvider,
    private val clock: () -> Long = ::defaultNowMs,
    private val logger: Logger = Logger.withTag("AiCoach"),
    private val benchProbe: BenchProbe = NoOpBenchProbe,
) : AiCoachOrchestrator {

    override fun explainMoveStreaming(request: MoveCoachRequest): Flow<MoveCoachEvent> = flow {
        val context = runCatching { contextProvider() }.getOrElse {
            emit(complete(MoveCoachResult.Failed("context snapshot failed: ${it.message}")))
            return@flow
        }
        val decision = AiRoutePolicyDecider.decide(request.policy, context)
        when (decision) {
            is AiRoutePolicyDecider.Decision.Route -> emit(runOnDevice(request, decision.route))
            is AiRoutePolicyDecider.Decision.RunCloud -> emit(complete(MoveCoachResult.Failed("Cloud route not supported in onDeviceAi orchestrator")))
            is AiRoutePolicyDecider.Decision.FallBack ->
                emit(fallback(request, decision.reason))
        }
    }

    override suspend fun explainMove(request: MoveCoachRequest): MoveCoachResult {
        var result: MoveCoachResult = MoveCoachResult.Failed("orchestrator produced no result")
        explainMoveStreaming(request).collect { event ->
            if (event is MoveCoachEvent.Complete) result = event.result
        }
        return result
    }

    private suspend fun runOnDevice(request: MoveCoachRequest, route: VendorRoute): MoveCoachEvent {
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
        request: MoveCoachRequest,
        generator: OnDeviceTextGenerator,
        startMs: Long,
    ): MoveCoachEvent {
        val prompt = MoveCoachPromptBuilder.build(request)
        val outcome = collectGenerate(request, generator, prompt, startMs)
            ?: return fallback(request, AiRoutePolicyDecider.FALLBACK_TIMEOUT)
        benchProbe.onRawOutput(outcome.rawText)

        // Parse JSON
        val parsed = try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            json.decodeFromString<MoveCoachResponse>(outcome.rawText)
        } catch (e: Exception) {
            logger.w(e) { "Failed to parse structured output" }
            return fallback(request, AiRoutePolicyDecider.FALLBACK_VALIDATION)
        }

        // Validate groundedness after deserialization
        val validation = MoveCoachResponseValidator.validate(parsed.explanation, request)

        return when (validation) {
            is MoveCoachResponseValidator.Result.Valid -> success(parsed, outcome.metrics)
            is MoveCoachResponseValidator.Result.Invalid -> {
                logger.w { "Validation failed: ${validation.reason}" }
                fallback(request, AiRoutePolicyDecider.FALLBACK_VALIDATION)
            }
        }
    }

    private suspend fun collectGenerate(
        request: MoveCoachRequest,
        generator: OnDeviceTextGenerator,
        prompt: AiGenerationRequest,
        startMs: Long,
    ): GenerationOutcome? {
        val collected = StringBuilder()
        var firstTokenMs: Long? = null
        var finalMetrics: AiInferenceMetrics? = null

        benchProbe.onGenerateStart()
        val completed = withTimeoutOrNull(request.policy.latencyBudget.completeMs) {
            generator.generate(prompt).collect { piece ->
                if (firstTokenMs == null) {
                    firstTokenMs = clock() - startMs
                    benchProbe.onFirstToken()
                }
                when (piece) {
                    is AiTokenOrFinal.Token -> collected.append(piece.text)
                    is AiTokenOrFinal.Final -> {
                        collected.append(piece.text)
                        finalMetrics = piece.metrics
                    }
                }
            }
        } != null
        if (!completed) return null

        val rawText = collected.toString()
        val metrics = finalMetrics ?: AiInferenceMetrics(
            firstTokenMs = firstTokenMs,
            completeMs = clock() - startMs,
            tokenCount = countTokens(rawText),
            route = AiRoute.OnDevice,
        )
        benchProbe.onGenerateComplete(metrics.tokenCount)
        return GenerationOutcome(
            rawText = rawText,
            metrics = metrics,
        )
    }

    private fun success(
        response: MoveCoachResponse,
        metrics: AiInferenceMetrics,
        confidence: ExplanationConfidence = ExplanationConfidence.HIGH,
    ): MoveCoachEvent = complete(
        MoveCoachResult.Success(
            MoveCoachExplanation(
                headline = response.headline.trim().ifBlank { response.explanation.take(60) },
                explanation = response.explanation,
                confidence = confidence,
                route = AiRoute.OnDevice,
                metrics = metrics,
            )
        )
    )

    private fun fallback(request: MoveCoachRequest, reason: String): MoveCoachEvent {
        benchProbe.onFallback(reason)
        return complete(
            MoveCoachResult.FellBack(
                text = MoveCoachFallback.build(request),
                reason = reason,
            )
        )
    }

    private fun complete(result: MoveCoachResult): MoveCoachEvent =
        MoveCoachEvent.Complete(result)

    private fun countTokens(text: String): Int =
        text.split(Regex("\\s+")).count { it.isNotBlank() }

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
