package com.example.ondeviceai.cactus

import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.AiGenerationRequest
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.OnDeviceTextGenerator
import com.example.ondeviceai.RuleLookupTool
import com.example.ondeviceai.RulePassage
import com.example.ondeviceai.RulesQaAnswerer
import com.example.ondeviceai.RulesQaGrounding
import com.example.ondeviceai.RulesQaModelOutput
import kotlinx.coroutines.CancellationException

import com.example.ondeviceai.VendorRoute
import com.example.ondeviceai.VendorRouteExecutor
import com.example.ondeviceai.AiRouteExecutor
import com.example.ondeviceai.AiRoutePolicies
import com.example.ondeviceai.AiContextSnapshot
import com.example.ondeviceai.AiUserSetting

/**
 * Android rules Q&A uses structured-output prompting, not native function calling.
 *
 * The small Cactus model first emits a strict `lookup_rule` JSON envelope. Kotlin executes the
 * real offline lookup and sends those passages in a second turn. Keeping this distinction explicit
 * avoids presenting Cactus prompt choreography as a tool-calling API.
 */
class StructuredOutputRulesQaAnswerer(
    private val executor: AiRouteExecutor,
    private val lookupTool: RuleLookupTool,
) : RulesQaAnswerer {

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
            val refined = parseLookupQuery(
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
            )?.let { lookupTool.lookup(it) }.orEmpty()

            // Only the best few reach the prompt. Handing a small model four passages and asking it
            // to "answer from these" reliably produces a transcript of all four -- observed on
            // device: three passages echoed back with their ids and nothing resembling an answer.
            // The top hit is the answer; the rest are context it does not need in order to phrase one.
            val passages = (fromQuestion + refined).distinctBy { it.id }.take(ANSWER_PASSAGES)
            if (passages.isEmpty()) return ungrounded("")

            val passageText = passages.joinToString("\n") { passage ->
                "[${passage.id}] ${passage.title}: ${passage.text}"
            }
            val answer = generator.runTurn(
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
            RulesQaModelOutput(
                // Uncited or over-long model prose falls back to the passage text, not to
                // RulesQaFallback -- see RulesQaGrounding. Retrieval succeeded; the user gets the rule.
                text = RulesQaGrounding.answerOrReference(answer, passages),
                retrievedPassageIds = passages.map { it.id },
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // A native generation failure must not discard a retrieval that already succeeded.
            grounded(fromQuestion)
        } finally {
            try {
                generator.close()
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
            }
        }
        return text.toString().trim()
    }

    private fun parseLookupQuery(output: String): String? {
        val match = LOOKUP_ENVELOPE.find(output) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .trim()
            .takeIf { it.isNotEmpty() && it.length <= 160 }
    }

    private fun ungrounded(text: String) = RulesQaModelOutput(
        text = text,
        retrievedPassageIds = emptyList(),
    )

    /**
     * Retrieval succeeded but the model never contributed (absent, unavailable, or it threw).
     * Empty passages collapse to [ungrounded], which is the one case that should reach
     * `RulesQaFallback`: nothing was found, so there is genuinely nothing to report.
     */
    private fun grounded(passages: List<RulePassage>) = RulesQaModelOutput(
        text = RulesQaGrounding.composeFromPassages(passages),
        retrievedPassageIds = passages.map { it.id },
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
        val LOOKUP_ENVELOPE = Regex(
            // NOTE: the closing brace must be escaped as `\}`. A bare `}` compiles on the JVM
            // (lenient java.util.regex) but Android's ICU-backed engine rejects it with a
            // PatternSyntaxException at class-init, crashing the app on device. JVM-only tests
            // (desktopTest) can't catch this — it is Android-runtime-specific.
            """\s*\{\s*"tool"\s*:\s*"lookup_rule"\s*,\s*"query"\s*:\s*"((?:\\.|[^"\\])*)"\s*\}\s*""",
        )
    }
}
