package com.example.ondeviceai.cactus

import co.touchlab.kermit.Logger
import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.AiGenerationRequest
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.OnDeviceTextGenerator
import com.example.ondeviceai.RuleLookupTool
import com.example.ondeviceai.RulePassage
import com.example.ondeviceai.RulesQaAnswerer
import com.example.ondeviceai.RulesQaGrounding
import com.example.ondeviceai.RulesQaModelOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

import com.example.ondeviceai.AiToolParameter
import com.example.ondeviceai.AiToolSpec
import com.example.ondeviceai.AiRouteExecutor
import com.example.ondeviceai.AiRoutePolicies
import com.example.ondeviceai.VendorRoute

/**
 * Android rules Q&A uses either native tool calling or structured-output prompting, depending on
 * whether the underlying OnDeviceTextGenerator natively supports it.
 *
 * It first routes a lookup query to the offline lookup. The passages are then sent in a second
 * turn. Keeping this distinction explicit keeps offline capabilities separate from model generation.
 */
class OnDeviceRulesQaAnswerer(
    private val executor: AiRouteExecutor,
    private val lookupTool: RuleLookupTool,
) : RulesQaAnswerer {

    private val logger = Logger.withTag("RulesQa")

    override suspend fun answer(question: String, route: VendorRoute): RulesQaModelOutput {
        // Retrieval runs FIRST, on the user's own question, and is never gated on the model.
        //
        // It used to be: the model had to emit a parseable `lookup_rule` envelope before any lookup
        // happened at all, so a 270M model fumbling its JSON meant a corpus that contains the answer
        // was never even searched. The question is itself a good BM25 query -- "Game is a draw when
        // only kings remain?" scores `draw-dead-position` first by a wide margin -- so the model was
        // a single point of failure in front of a step that already worked without it.
        val fromQuestion = lookupTool.lookup(question)

        val generator = executor.execute(route) ?: return grounded(fromQuestion)
        return try {
            if (generator.status() !is AiAvailability.Available) return grounded(fromQuestion)

            // The structured-output turn is now a *refinement*: its query can surface passages the
            // raw question misses, and if it produces nothing usable we simply keep what retrieval
            // already found. Question hits stay first because they are the ones we can vouch for.
            val supportsToolCalling = generator.supportsTools
            val deadline = TimeSource.Monotonic.markNow() + AiRoutePolicies.rulesQaOffline.latencyBudget.completeMs.milliseconds

            val refined = withTimeoutOrNull(max(0, (deadline - TimeSource.Monotonic.markNow()).inWholeMilliseconds)) {
                if (supportsToolCalling) {
                    var toolQuery: String? = null
                    generator.generate(
                        AiGenerationRequest(
                            systemPrompt = "You route chess-rules questions to an offline lookup.",
                            userPrompt = "Decide what rule to retrieve for this question: $question",
                            maxOutputTokens = 80,
                            temperature = 0.0,
                            tools = listOf(
                                AiToolSpec(
                                    name = "lookup_rule",
                                    description = "Search the offline rules corpus",
                                    parameters = mapOf(
                                        "query" to AiToolParameter(
                                            type = "string",
                                            description = "short search query",
                                        )
                                    ),
                                )
                            )
                        )
                    ).collect { output ->
                        if (output is AiTokenOrFinal.ToolCall && output.name == "lookup_rule") {
                            toolQuery = output.arguments["query"]
                        }
                    }
                    toolQuery
                } else {
                    parseLookupQuery(
                        generator.runTurn(
                            AiGenerationRequest(
                                systemPrompt = LOOKUP_SYSTEM_PROMPT,
                                userPrompt = """
                                    Structured-output request. Decide what rule to retrieve for this question:
                                    $question

                                    Return only {"tool":"lookup_rule","query":"short search query"}.
                                """.trimIndent(),
                                maxOutputTokens = 80,
                                temperature = 0.0,
                            ),
                        ),
                    )
                }
            }?.let { lookupTool.lookup(it) }.orEmpty()

            // Only the best few reach the prompt. Handing a small model four passages and asking it
            // to "answer from these" reliably produces a transcript of all four -- observed on
            // device: three passages echoed back with their ids and nothing resembling an answer.
            // The top hit is the answer; the rest are context it does not need in order to phrase one.
            val passages = (fromQuestion + refined).distinctBy { it.id }.take(ANSWER_PASSAGES)
            if (passages.isEmpty()) return ungrounded("")

            val passageText = passages.joinToString("\n") { passage ->
                "[${passage.id}] ${passage.title}: ${passage.text}"
            }
            val answer = withTimeoutOrNull(max(0, (deadline - TimeSource.Monotonic.markNow()).inWholeMilliseconds)) {
                generator.runTurn(
                    AiGenerationRequest(
                        systemPrompt = ANSWER_SYSTEM_PROMPT,
                        userPrompt = """
                            Question: $question

                            Retrieved offline rules:
                            $passageText

                            Reply with one or two sentences that answer the question directly, then the
                            id of the rule you used in square brackets. Do not list the rules and do not
                            copy them word for word.

                            Example of the shape: Yes — with no way left to force mate the game is
                            drawn [${passages.first().id}].
                        """.trimIndent(),
                        // The example's id is deliberately a *retrieved* one: a model that copies the
                        // example verbatim still produces a citation the validator accepts, instead of
                        // inventing `[rule-id]` and failing closed to the passage text.
                        maxOutputTokens = 160,
                        temperature = 0.2,
                    ),
                )
            } ?: return grounded(passages)
            
            RulesQaGrounding.rejectionReason(answer, passages)?.let { reason ->
                logger.i { "model wording refused ($reason); answering from the passage instead" }
            }
            RulesQaModelOutput(
                // Uncited or over-long model prose falls back to the passage text, not to
                // RulesQaFallback -- see RulesQaGrounding. Retrieval succeeded; the user gets the rule.
                text = RulesQaGrounding.answerOrReference(answer, passages),
                retrievedPassageIds = passages.map { it.id },
                retrievedPassages = passages,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // A native generation failure must not discard a retrieval that already succeeded.
            grounded(fromQuestion)
        } finally {
            try {
                generator.release()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Closing a failed native generator must not replace the deterministic fallback.
            }
        }
    }

    private suspend fun OnDeviceTextGenerator.runTurn(request: AiGenerationRequest): String {
        val text = StringBuilder()
        generate(request).collect { output ->
            when (output) {
                is AiTokenOrFinal.Token -> text.append(output.text)
                is AiTokenOrFinal.Final -> text.append(output.text)
                is AiTokenOrFinal.ToolCall -> {}
            }
        }
        return text.toString().trim()
    }

    private fun parseLookupQuery(output: String): String? {
        return Regex("""\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}""").findAll(output)
            .mapNotNull { 
                try { 
                    Json.parseToJsonElement(it.value).jsonObject 
                } catch (_: Throwable) { 
                    logger.d { "parseLookupQuery candidate failed to parse: ${it.value}" }
                    null 
                } 
            }
            .firstOrNull { it["tool"]?.jsonPrimitive?.content == "lookup_rule" }
            ?.let { obj -> 
                val query = obj["query"] ?: obj["arguments"]?.jsonObject?.get("query")
                if (query == null) {
                    logger.d { "lookup_rule envelope missing query: $obj" }
                }
                query
            }
            ?.jsonPrimitive?.content
            ?.takeIf { it.isNotEmpty() && it.length <= 160 }
    }

    private fun ungrounded(text: String) = RulesQaModelOutput(
        text = text,
        retrievedPassageIds = emptyList(),
        retrievedPassages = emptyList(),
    )

    /**
     * Retrieval succeeded but the model never contributed (absent, unavailable, or it threw).
     * Empty passages collapse to [ungrounded], which is the one case that should reach
     * `RulesQaFallback`: nothing was found, so there is genuinely nothing to report.
     */
    private fun grounded(passages: List<RulePassage>) = RulesQaModelOutput(
        text = RulesQaGrounding.composeFromPassages(passages),
        retrievedPassageIds = passages.map { it.id },
        retrievedPassages = passages,
    )

    private companion object {
        /**
         * How many retrieved passages reach the answer prompt. Two, not four: a 270M model asked to
         * answer from four passages echoes four passages. This is also what `Sources:` reports, so
         * the user is shown exactly what grounded the answer.
         */
        const val ANSWER_PASSAGES = 2

        const val LOOKUP_SYSTEM_PROMPT = """
            You route chess-rules questions to an offline lookup. This is structured-output
            prompting, so output one lookup_rule JSON object and no prose.
        """
        const val ANSWER_SYSTEM_PROMPT = """
            You answer chess-rules questions only from retrieved offline passages. Answer the
            question in your own words -- never reproduce the passages as a list. Be concise,
            never invent a rule, and cite an exact passage id in square brackets.
        """
    }
}
