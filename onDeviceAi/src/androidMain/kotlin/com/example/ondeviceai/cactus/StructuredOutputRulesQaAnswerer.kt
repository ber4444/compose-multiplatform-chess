package com.example.ondeviceai.cactus

import com.example.ondeviceai.AiAvailability
import com.example.ondeviceai.AiGenerationRequest
import com.example.ondeviceai.AiTokenOrFinal
import com.example.ondeviceai.OnDeviceTextGenerator
import com.example.ondeviceai.RuleLookupTool
import com.example.ondeviceai.RulesQaAnswerer
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
        val generator = executor.execute(route) ?: return ungrounded("")
        return try {
            if (generator.status() !is AiAvailability.Available) return ungrounded("")

            val firstTurn = generator.runTurn(
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
            )
            val lookupQuery = parseLookupQuery(firstTurn) ?: return ungrounded(firstTurn)
            val passages = lookupTool.lookup(lookupQuery)
            if (passages.isEmpty()) return ungrounded(firstTurn)

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

                        Answer from these passages only and cite at least one exact [passage-id].
                    """.trimIndent(),
                    maxOutputTokens = 160,
                    temperature = 0.2,
                ),
            )
            RulesQaModelOutput(
                text = answer,
                retrievedPassageIds = passages.map { it.id },
            )
        } catch (ce: CancellationException) {
            throw ce
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
        val match = LOOKUP_ENVELOPE.matchEntire(output) ?: return null
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

    private companion object {
        const val LOOKUP_SYSTEM_PROMPT = """
            You route chess-rules questions to an offline lookup. This is structured-output
            prompting, so output one lookup_rule JSON object and no prose.
        """
        const val ANSWER_SYSTEM_PROMPT = """
            You answer chess-rules questions only from retrieved offline passages. Be concise,
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
