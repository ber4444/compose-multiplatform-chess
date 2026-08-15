package com.example.ondeviceai

sealed interface AiAvailability {
    data object Available : AiAvailability
    data class Downloadable(val requiresUserConfirmation: Boolean = true) : AiAvailability
    data class Downloading(val progress: Float? = null) : AiAvailability
    data object Unavailable : AiAvailability
    data object Busy : AiAvailability
    data class Error(val message: String) : AiAvailability
}

data class AiToolParameter(
    val type: String, // e.g. "string", "integer"
    val description: String,
)

data class AiToolSpec(
    val name: String,
    val description: String,
    val parameters: Map<String, AiToolParameter>,
)

data class AiGenerationRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val maxOutputTokens: Int,
    val temperature: Double = 0.2,
    /**
     * B15: cut the completion before an n-gram of this size reoccurs; `null` disables the rule.
     * This — plus [stopSequences] — is applied post-hoc by [withAntiRepetitionGuard], not at
     * sampling time. No runtime this project ships to (Foundation Models, LiteRT-LM desktop/wasm,
     * ML Kit) exposes a repetition/frequency penalty or logit-level n-gram block through its Kotlin
     * or JS API — checked against each pinned SDK, see
     * `docs/benchmarks/on-device-ai/b15-generation-side-repetition-2026-08.md`. Only top-k/top-p/
     * temperature/seed are available anywhere, and none of those is a targeted repetition penalty.
     */
    val noRepeatNgramSize: Int? = 4,
    val stopSequences: List<String> = emptyList(),
    val tools: List<AiToolSpec> = emptyList(),
)

sealed interface AiTokenOrFinal {
    /** A delta. Consumers concatenate these in arrival order to rebuild the answer. */
    data class Token(val text: String) : AiTokenOrFinal
    data class ToolCall(val name: String, val arguments: Map<String, String>) : AiTokenOrFinal

    /**
     * Terminal event, and the only carrier of [metrics].
     *
     * **[text] must be empty when the generator has emitted any [Token].** It exists for
     * non-streaming backends, where Final is the whole answer. Every shipped streaming generator
     * emits `text = ""` — iOS `FoundationModelsBridge`, desktop and wasm LiteRT-LM, and
     * `FakeTextGenerator`.
     *
     * `MlKitPromptGenerator` did not, and consumers were appending Token *and* Final text into one
     * buffer, so the complete answer was concatenated with itself. It was recorded as an "AICore
     * repetition loop" in `evals/scorecard.md` and reported as a model defect for a month. Because
     * the test fake honours the contract, no `commonTest` could reproduce it.
     *
     * How far the duplicate travelled depended on the surface, which is worth knowing before
     * trusting any of the affected measurements. `rawText` was always doubled, and
     * `benchProbe.onRawOutput` fires before validation — so the bench JSONL and the scorecard's
     * "314 chars against a 300 cap" measured the doubled string, which is where the misattribution
     * came from. **Game Summary** runs with no response validator at all, so the duplicate reached
     * the user intact. **Move Coach** had a partial net in `MoveCoachResponseValidator`'s
     * `deduplicateSentences`, which collapses repeated sentences — but only when the copies key
     * identically, and `stripConversationalFiller` strips a leading "Okay, so" from the first copy
     * and not the second, so a duplicate could still survive it.
     *
     * Consumers now ignore [text] once a [Token] has arrived, so violating this is no longer fatal
     * — but the invariant is the one that keeps metrics and text on separate channels, so hold to
     * it in new backends rather than relying on the consumer's tolerance.
     */
    data class Final(
        val text: String,
        val metrics: AiInferenceMetrics,
    ) : AiTokenOrFinal
}

data class AiInferenceMetrics(
    val firstTokenMs: Long?,
    val completeMs: Long?,
    val tokenCount: Int,
    val route: AiRoute,
    val fallbackReason: AiRoutePolicyDecider.FallbackReason? = null,
)

interface OnDeviceTextGenerator {
    suspend fun status(): AiAvailability
    suspend fun warmup()
    fun generate(request: AiGenerationRequest): kotlinx.coroutines.flow.Flow<AiTokenOrFinal>
    /**
     * Generators are singletons owned by the executor. Orchestrators borrow them for a generation
     * and MUST call [release] when done. They must NOT close/destroy the underlying engine.
     */
    suspend fun release()
    val supportsTools: Boolean get() = false
}


object UnsupportedTextGenerator : OnDeviceTextGenerator {
    override suspend fun status(): AiAvailability = AiAvailability.Unavailable
    override suspend fun warmup() = Unit
    override fun generate(
        request: AiGenerationRequest,
    ): kotlinx.coroutines.flow.Flow<AiTokenOrFinal> = kotlinx.coroutines.flow.flowOf()
    override suspend fun release() = Unit
}


/** Multiplatform wall-clock ms (no kotlinx-datetime dep — avoids export headaches on iOS). */
internal expect fun defaultNowMs(): Long
