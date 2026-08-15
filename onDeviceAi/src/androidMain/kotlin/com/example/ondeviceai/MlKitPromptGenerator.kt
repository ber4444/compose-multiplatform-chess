package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig

class MlKitPromptGenerator(private val routePreference: com.example.ondeviceai.ModelPreference) : OnDeviceTextGenerator {
    
    private val modelConfig = modelConfig {
        // STABLE, not PREVIEW. PREVIEW asks AICore for a feature most builds do not provision, and
        // the failure is opaque: `[ErrorCode 606] FEATURE_NOT_FOUND: Feature -1 is not available`,
        // caught by status() and reported as a plain Unavailable, so the device looked like it
        // simply had no AICore. Observed on a Pixel 10 Pro XL with AICore present and working.
        // STABLE is also the documented default, so the Google sample — which sets no modelConfig
        // at all — gets it implicitly.
        releaseStage = ModelReleaseStage.STABLE
        preference = if (this@MlKitPromptGenerator.routePreference == com.example.ondeviceai.ModelPreference.FAST) {
            ModelPreference.FAST
        } else {
            ModelPreference.FULL
        }
    }
    
    private val genConfig = generationConfig {
        this.modelConfig = this@MlKitPromptGenerator.modelConfig
    }
    
    private val model = Generation.getClient(genConfig)

    override suspend fun status(): AiAvailability {
        // Any non-Available result — including Error — keeps this route out of
        // probeAvailableLocalVendors' list, so the decider never selects it and the coach stays
        // deterministic. This used to hardcode Available regardless of real device support, so ML
        // Kit was picked on devices that could not run it and failed later at generation with e.g.
        // ErrorCode -101. checkStatus() itself may throw rather than cleanly return UNAVAILABLE on
        // such devices, so both outcomes are covered here.
        //
        // Note the status depends on `routePreference`: FAST and FULL are different base models and
        // therefore different AICore features. A Pixel 10 Pro XL answers UNAVAILABLE for FAST
        // (Feature 645) and AVAILABLE for FULL, which is why the probe tries both.
        return try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> AiAvailability.Available
                FeatureStatus.DOWNLOADABLE -> AiAvailability.Downloadable()
                FeatureStatus.DOWNLOADING -> AiAvailability.Downloading()
                else -> AiAvailability.Unavailable
            }
        } catch (e: Exception) {
            AiAvailability.Error(e.message ?: "checkStatus failed")
        }
    }

    override suspend fun warmup() {
        // The feature is delivered on demand and `download()` was never called, so a device
        // reporting DOWNLOADABLE stayed that way forever: status() answered "not Available", the
        // probe left this route out of the vendor list, and AICore looked absent on hardware that
        // has it. `download()` is a Flow and completes when the feature is installed.
        runCatching {
            android.util.Log.d("MlKitPrompt", "base model: " + model.getBaseModelName())
        }
        runCatching {
            // The sample's rule is "anything that isn't AVAILABLE or UNAVAILABLE gets downloaded"
            // (BaseActivity.checkFeatureStatus). DOWNLOADING has to be in here as well as
            // DOWNLOADABLE: collecting download() on an in-flight fetch is how you *await* it, and
            // without that a device that is mid-provision is written off as having no feature.
            val status = model.checkStatus()
            if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
                android.util.Log.d("MlKitPrompt", "feature status $status; requesting download")
                // Collecting to termination is the await — the flow completes once AICore reaches a
                // terminal state. DownloadFailed arrives as an *emitted status*, not a thrown
                // exception, so the enclosing runCatching never sees it; it has to be logged here
                // or a failed download is indistinguishable from a successful one.
                model.download().collect { downloadStatus ->
                    if (downloadStatus is DownloadStatus.DownloadFailed) {
                        android.util.Log.w("MlKitPrompt", "download failed", downloadStatus.e)
                    } else {
                        android.util.Log.d("MlKitPrompt", "download: $downloadStatus")
                    }
                }
            }
        }.onFailure { android.util.Log.w("MlKitPrompt", "download threw", it) }
        runCatching { model.warmup() }
    }

    override fun generate(request: AiGenerationRequest): Flow<AiTokenOrFinal> = flow {
        val sysInst = SystemInstruction(request.systemPrompt)
        val userPart = TextPart(request.userPrompt)
        
        val genRequest = generateContentRequest(sysInst, userPart) {
            temperature = request.temperature.toFloat()
            maxOutputTokens = request.maxOutputTokens
            // Note: ML Kit GenAI Prompt API currently doesn't expose a stopSequences setter.
        }
        
        // Let a generation failure (e.g. AICore not installed — ErrorCode -101) propagate as a real
        // exception rather than swallowing it here. DefaultAiCoachOrchestrator.runOnDevice already
        // wraps generation in a catch (t: Throwable) that reports a clean "generation error: ..."
        // fallback; emitting the error as a fake successful JSON payload instead made the real cause
        // invisible — it surfaced downstream as an opaque "model output failed validation" once the
        // error string failed to parse against the {headline, explanation} schema.
        val start = System.currentTimeMillis()
        var firstTokenMs: Long? = null
        model.generateContentStream(genRequest).collect { response ->
            response.candidates.firstOrNull()?.text?.let { chunk ->
                if (firstTokenMs == null) firstTokenMs = System.currentTimeMillis() - start
                emit(AiTokenOrFinal.Token(chunk))
            }
        }
        // `text = ""`, matching every other generator (iOS FoundationModelsBridge, desktop and wasm
        // LiteRT-LM, and FakeTextGenerator). Tokens carry the text; Final carries the metrics.
        //
        // This used to emit the accumulated full text, and every orchestrator appends *both* Token
        // and Final text into one buffer — so the complete answer was concatenated with itself.
        // That was recorded in evals/scorecard.md as an "AICore repetition loop" and in the 2026-08
        // latency note as a model defect; it was ours. Game Summary, which has no validator, showed
        // the duplicate to the user; Move Coach's deduplicateSentences absorbed it only when the
        // two copies keyed identically. See AiTokenOrFinal.Final for the full blast radius.
        // The tell was the arithmetic: a 314-char output against a 300-char cap is one 157-char
        // answer doubled, not a model degenerating. FakeTextGenerator emits `text = ""` like the
        // conforming generators, so no commonTest could reproduce it — DefaultAiCoachOrchestrator
        // now ignores Final text once a Token has arrived, which is the half that is testable.
        //
        // ML Kit still does not report a token count. Emit 0 ("unknown") rather than a character
        // length so the bench JSONL under docs/benchmarks/on-device-ai/ doesn't ingest bad data —
        // but the two latency figures are real now. They were hardcoded 0L, and because the
        // orchestrator prefers Final's metrics whenever they are present, those zeros silently
        // replaced the timings it had just measured itself.
        emit(
            AiTokenOrFinal.Final(
                text = "",
                metrics = AiInferenceMetrics(
                    firstTokenMs = firstTokenMs,
                    completeMs = System.currentTimeMillis() - start,
                    tokenCount = 0,
                    route = AiRoute.OnDevice,
                ),
            ),
        )
    }.withAntiRepetitionGuard(
        // The other streaming backends chain this; ML Kit was the one that did not, so it had
        // neither of the two nets that would have caught the duplication.
        //
        // Post-hoc is the only option, and that is measured rather than assumed:
        // GenerateContentRequest.Builder (genai-prompt:1.0.0-beta4) exposes temperature/seed/topK/
        // candidateCount/maxOutputTokens/promptPrefix/cachedContextName/enableThinking — no
        // repetition or frequency penalty, no n-gram block, and no stop-sequence setter. Neither do
        // the other three runtimes' samplers. See b15-generation-side-repetition-2026-08.md.
        ngramSize = request.noRepeatNgramSize,
        stopSequences = request.stopSequences,
    )

    override suspend fun release() {
        model.close()
    }
}
