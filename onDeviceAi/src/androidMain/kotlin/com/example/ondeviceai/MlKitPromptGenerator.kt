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
        // caught by status() and reported as a plain Unavailable, so the device silently fell
        // through to Cactus and looked like it simply had no AICore. Observed on a Pixel 10 Pro XL
        // with AICore present and working.
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
        // VendorRouteExecutor's own fallback is `if (mlkit.status() is Available) mlkit else
        // getCactus()` — any non-Available result here (including Error) already routes to Cactus.
        // This used to hardcode Available regardless of real device support, which meant Cactus was
        // silently unreachable on any device without working AICore: ML Kit would be picked, fail
        // generation with e.g. ErrorCode -101, and never fall through. checkStatus() itself might
        // throw rather than cleanly return UNAVAILABLE on such devices — either outcome must still
        // fall through, so both paths are covered.
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
        // decider fell through to Cactus, and AICore looked absent on hardware that has it.
        // `download()` is a Flow and completes when the feature is installed.
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
        var fullText = ""
        model.generateContentStream(genRequest).collect { response ->
            response.candidates.firstOrNull()?.text?.let { chunk ->
                fullText += chunk
                emit(AiTokenOrFinal.Token(chunk))
            }
        }
        // ML Kit does not report a token count. Emit 0 ("unknown") rather than a character length
        // so downstream benchmarks don't ingest bad data.
        emit(AiTokenOrFinal.Final(fullText, AiInferenceMetrics(0L, 0L, 0, AiRoute.OnDevice)))
    }

    override suspend fun release() {
        model.close()
    }
}
