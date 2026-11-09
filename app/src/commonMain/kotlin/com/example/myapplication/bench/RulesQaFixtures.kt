package com.example.myapplication.bench

import com.example.ondeviceai.AiContextSnapshot
import com.example.ondeviceai.AiRoute
import com.example.ondeviceai.AiRoutePolicies
import com.example.ondeviceai.AiRoutePolicyDecider
import com.example.ondeviceai.AiUserSetting
import com.example.ondeviceai.BundledRuleLookupTool
import com.example.ondeviceai.DefaultRulesQaOrchestrator
import com.example.ondeviceai.RulesQaAnswerer
import com.example.ondeviceai.RulesQaFallback
import com.example.ondeviceai.RulesQaGrounding
import com.example.ondeviceai.RulesQaModelOutput
import com.example.ondeviceai.RulesQaResult
import com.example.ondeviceai.VendorRoute
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

@Serializable
internal data class RulesQaFixture(val question: String, val expectedPassageId: String?)

internal object RulesQaFixtures {
    private val lookup = BundledRuleLookupTool()
    private val citation = Regex("""\[([^\[\]]+)\]""")

    fun load(text: String): List<RulesQaFixture> =
        Json.decodeFromString<List<RulesQaFixture>>(text).also { fixtures ->
            require(fixtures.isNotEmpty() && fixtures.all { it.question.isNotBlank() }) {
                "Rules Q&A needs at least one fixture and every question must be nonblank"
            }
        }

    suspend fun measure(
        fixture: RulesQaFixture,
        vendors: List<VendorRoute>,
        deviceModel: String,
        osVersion: String,
        answerer: (record: (RulesQaModelOutput?, String?) -> Unit) -> RulesQaAnswerer,
    ): String {
        val ranked = lookup.rankedLookup(fixture.question)
        val (retrieved, score) = ranked.firstOrNull() ?: (null to null)
        val floor = RulesQaGrounding.composeFromPassages(ranked.map { it.first })
            .ifEmpty { RulesQaFallback.TEXT }

        var model: RulesQaModelOutput? = null
        var modelFallback: String? = null
        val context = AiContextSnapshot(
            availableLocalVendors = vendors,
            isAppForegrounded = true,
            userSetting = AiUserSetting.OFFLINE_ONLY,
        )
        val orchestrator = DefaultRulesQaOrchestrator(
            answerer = answerer { output, reason ->
                model = output
                modelFallback = reason
            },
            lookupTool = lookup,
            contextProvider = { context },
        )

        val start = TimeSource.Monotonic.markNow()
        val result = orchestrator.answer(fixture.question)
        val elapsed = start.elapsedNow().inWholeMilliseconds
        val fallback = when (result) {
            is RulesQaResult.FellBack -> result.reason.description
            is RulesQaResult.Success -> (result.route as? AiRoute.Fallback)?.reason?.description ?: modelFallback
        }
        val (kind, answer) = when (result) {
            is RulesQaResult.FellBack -> "Fallback" to result.text
            is RulesQaResult.Success -> (if (fallback == null) "Model" else "Grounded") to result.text
        }
        val route = (AiRoutePolicyDecider.decide(AiRoutePolicies.rulesQaOffline, context)
            as? AiRoutePolicyDecider.Decision.RunOnDevice)?.route
        val modelIdentifier = when (route) {
            is VendorRoute.MlKitPrompt -> "mlkit-aicore-${route.preference.name.lowercase()}"
            is VendorRoute.AppleFoundationModels -> "FoundationModels"
            null -> "none"
            else -> route::class.simpleName ?: "unknown"
        }

        return Json.encodeToString(
            RulesQaBenchRow(
                question = fixture.question,
                expectedPassageId = fixture.expectedPassageId,
                retrievedPassageId = retrieved?.id,
                retrievalScore = score,
                groundedAnswer = floor,
                modelAnswer = model?.text,
                modelPassageIds = model?.retrievedPassageIds.orEmpty(),
                citedPassageIds = model?.text?.let { text ->
                    citation.findAll(text).map { it.groupValues[1] }.distinct().toList()
                }.orEmpty(),
                answer = answer,
                kind = kind,
                fallbackReason = fallback,
                elapsedMs = elapsed,
                modelIdentifier = modelIdentifier,
                deviceModel = deviceModel,
                osVersion = osVersion,
            ),
        )
    }

    @Serializable
    private data class RulesQaBenchRow(
        val question: String,
        val expectedPassageId: String?,
        val retrievedPassageId: String?,
        val retrievalScore: Double?,
        val groundedAnswer: String,
        val modelAnswer: String?,
        val modelPassageIds: List<String>,
        val citedPassageIds: List<String>,
        val answer: String,
        val kind: String,
        val fallbackReason: String?,
        val elapsedMs: Long,
        val modelIdentifier: String,
        val deviceModel: String,
        val osVersion: String,
    )
}
