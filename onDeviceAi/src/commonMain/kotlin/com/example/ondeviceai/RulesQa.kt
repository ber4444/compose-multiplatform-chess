package com.example.ondeviceai

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class RulesQaModelOutput(
    val text: String,
    val retrievedPassageIds: List<String>,
    val retrievedPassages: List<RulePassage> = emptyList(),
)

fun interface RulesQaAnswerer {
    suspend fun answer(question: String, route: VendorRoute): RulesQaModelOutput
}

expect fun defaultRulesQaAnswerer(lookupTool: RuleLookupTool): RulesQaAnswerer?

/**
 * A corpus passage an answer cites, in the two forms the product needs: the [id] the validator and
 * the benchmarks key on, and the [title] a person can read.
 *
 * Both are required because they serve different readers. `CitationSanitizer` exists so raw ids
 * never reach the screen — printing `draw-dead-position` under a "Sources:" heading was doing the
 * very thing the sanitizer removes from the answer text one line above it.
 */
data class RuleCitation(val id: String, val title: String)

sealed interface RulesQaResult {
    data class Success(
        val text: String,
        val citations: List<RuleCitation>,
        val route: AiRoute,
    ) : RulesQaResult {
        /** Ids alone, for callers and tests that only key on identity. Derived, never stored. */
        val passageIds: List<String> get() = citations.map { it.id }
    }

    data class FellBack(
        val text: String,
        val reason: AiRoutePolicyDecider.FallbackReason,
    ) : RulesQaResult {
        /** Provenance (B11): derived from [reason], so the two can never disagree. See
         *  [OpeningExplainerResult.Fallback.route]. */
        val route: AiRoute get() = AiRoute.Fallback(reason)
    }
}

object RulesQaFallback {
    const val TEXT = "I couldn't verify that rule from the offline reference. A legal move must " +
        "leave your king safe; checkmate ends the game, while stalemate is a draw."
}

/**
 * The retrieved passage, as a finished answer.
 *
 * Rules Q&A is a *retrieval* feature: BM25 over the bundled corpus finds the rule deterministically,
 * and the model's only job is to phrase what was found. Those two steps fail independently, and the
 * phrasing step fails often — the Android runtime is a ~270M model, which frequently will not
 * reproduce an exact `[passage-id]` no matter how the prompt asks. Before this existed, that
 * phrasing failure discarded a *correct retrieval* and emitted [RulesQaFallback.TEXT], which tells a
 * user whose rule was found, and is sitting in memory, that it could not be found.
 *
 * So the retrieved passage is the floor. Mirrors `DeterministicCoach` on the move-coach side and the
 * house rule it encodes: code retrieves, the model only narrates. A narration failure costs the
 * user some fluency, never the answer.
 */
object RulesQaGrounding {

    /**
     * The top passage rendered as a cited answer, trimmed to the validator's budget.
     *
     * **Text only — the title is deliberately not prefixed.** The UI names the rule in its
     * `Sources:` line, so leading with "Draw by dead position and insufficient material: The game is
     * drawn when…" printed the same words twice on one screen. Attribution belongs in one place, and
     * dropping the prefix also makes this read the same way a model-phrased answer does: the body is
     * the answer, the source is named beside it.
     */
    fun composeFromPassages(passages: List<RulePassage>): String {
        val top = passages.firstOrNull() ?: return ""
        val citation = " [${top.id}]"
        val budget = RulesQaResponseValidator.MAX_OUTPUT_CHARS - citation.length
        val body = top.text
        if (budget <= 1) return citation.trim()
        return if (body.length <= budget) body + citation
        else body.take(budget - 1).trimEnd() + "…" + citation
    }

    /**
     * The model's wording when it survives [RulesQaResponseValidator], the passage itself otherwise.
     *
     * Deliberately re-uses the orchestrator's validator rather than a looser check of its own: an
     * answer that would be rejected downstream must not be preferred here, or the two disagree and
     * the user gets the fallback anyway.
     */
    fun answerOrReference(modelText: String, passages: List<RulePassage>): String =
        when (RulesQaResponseValidator.validate(modelText, passages.map { it.id })) {
            is RulesQaResponseValidator.Result.Valid -> modelText.trim()
            is RulesQaResponseValidator.Result.Invalid -> composeFromPassages(passages)
        }

    /**
     * Why the model's wording was refused, or `null` when it was kept. Diagnostic only.
     *
     * Falling back to the passage is now silent by construction — the user still gets a correct
     * answer, so nothing downstream reports a problem. That is the right product behaviour and the
     * wrong debugging behaviour: it is exactly the "six causes, one string" trap that made this
     * feature take four attempts to fix. Callers log this so `adb logcat -s RulesQa` can still tell
     * "the model wrote nothing" from "the model would not cite".
     */
    fun rejectionReason(modelText: String, passages: List<RulePassage>): String? =
        when (val result = RulesQaResponseValidator.validate(modelText, passages.map { it.id })) {
            is RulesQaResponseValidator.Result.Valid -> null
            is RulesQaResponseValidator.Result.Invalid -> result.reason
        }
}

object RulesQaResponseValidator {
    const val MAX_OUTPUT_CHARS = 600

    /**
     * Content words an answer must share with a retrieved passage to count as grounded in it.
     *
     * **This replaced "the answer must contain `[passage-id]`" as the primary rule, and the reason
     * is the runtime.** `PositionChatValidator` notes that "the id is the one thing a model copies
     * reliably from its prompt" — true of a cloud model, and the exact opposite on device. Measured
     * on a real device, gemma3-270m produced a correct, in-budget answer and simply did not echo the
     * bracketed id: `model wording refused (answer does not cite a retrieved passage id)`. The rule
     * was rejecting good answers for a formatting miss, which is precisely the mistake `:server`
     * already made and fixed (see "Why the provider LLM failed" in CLAUDE.md — a literal-string
     * check that scored verbatim copying and failed every correct paraphrase).
     *
     * Overlap is a *weaker* claim than a citation but a *stronger* grounding check: an id proves the
     * model read its prompt, whereas shared content words prove the answer is about the passage. The
     * bar is kept above zero for the reason `PositionChatValidator` gives — with no anchor at all,
     * any fluent invention validates.
     */
    const val MIN_SOURCE_OVERLAP = 2

    fun validate(text: String, retrievedPassageIds: List<String>): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.Invalid("empty answer")
        if (trimmed.length > MAX_OUTPUT_CHARS) return Result.Invalid("answer too long")
        if (retrievedPassageIds.isEmpty()) return Result.Invalid("no passage was retrieved")

        // An explicit citation is still the strongest signal, and still accepted first: it names the
        // passage the model itself claims to have used, rather than one inferred from word overlap.
        val cited = retrievedPassageIds.filter { id -> trimmed.contains("[$id]") }
        if (cited.isNotEmpty()) return Result.Valid(trimmed, cited.distinct())

        // Tags are removed before measuring overlap: `[draw-fifty-move]` tokenizes to "draw", "fifty",
        // "move", which would let an answer anchor itself on the id it just invented. An id is
        // evidence about the prompt, never about the prose.
        val answerWords = contentWords(trimmed.replace(CITATION_TAG, " "))
        val overlaps = retrievedPassageIds.mapNotNull { id ->
            val passage = rulePassageForId(id) ?: return@mapNotNull null
            val shared = contentWords("${passage.title} ${passage.text}").count { it in answerWords }
            if (shared >= MIN_SOURCE_OVERLAP) id to shared else null
        }
        if (overlaps.isEmpty()) {
            return Result.Invalid("answer is not anchored to any retrieved passage")
        }
        // Only the *best*-anchored passage is cited, not everything that clears the bar. Chess
        // vocabulary is small and repetitive, so a generic pair like "king"/"two" reaches
        // MIN_SOURCE_OVERLAP against almost anything: a measured answer about dead positions shared
        // 22 words with `draw-dead-position` and exactly those 2 with `castling-requirements`, and
        // the screen credited both. A citation the answer did not use is a false claim about where
        // the rule came from, which is worse than citing nothing. Ties keep retrieval order, so the
        // higher-ranked BM25 hit wins.
        val best = overlaps.maxOf { it.second }
        return Result.Valid(trimmed, overlaps.filter { it.second == best }.map { it.first })
    }

    /** Lowercased distinct words, minus the ones every sentence shares regardless of topic. */
    private fun contentWords(value: String): Set<String> = WORD.findAll(value.lowercase())
        .map { it.value }
        .filter { it.length > 2 && it !in STOP_WORDS }
        .toSet()

    private val WORD = Regex("[a-z0-9]+")
    private val CITATION_TAG = Regex("""\[[^\[\]]*\]""")
    private val STOP_WORDS = setOf(
        "and", "are", "any", "but", "can", "for", "from", "has", "have", "not", "the", "that",
        "this", "when", "with", "you", "your", "its", "may", "must", "was", "were", "will",
    )

    sealed interface Result {
        data class Valid(val text: String, val citedPassageIds: List<String>) : Result
        /** A diagnostic naming the broken rule; the caller maps every rejection to
         *  [AiRoutePolicyDecider.FallbackReason.Validation]. Mirrors [MoveCoachResponseValidator]. */
        data class Invalid(val reason: String) : Result
    }
}

class DefaultRulesQaOrchestrator(
    private val answerer: RulesQaAnswerer,
    private val lookupTool: RuleLookupTool,
    private val contextProvider: suspend () -> AiContextSnapshot,
) {
    // Not a constructor parameter: contextProvider is passed as a trailing lambda at every call
    // site, and this module is published, so appending a param is both a source and a binary break.
    // lookupTool was added deliberately as a source break covered by the 0.3.0 bump.
    private val logger: Logger = Logger.withTag("RulesQa")

    suspend fun answer(question: String): RulesQaResult {
        val normalizedQuestion = question.trim()
        if (normalizedQuestion.isEmpty()) {
            // The one case with nothing to retrieve, so the one case that skips the floor.
            logger.w { "Rules Q&A fallback triggered: empty question" }
            return RulesQaResult.FellBack(
                text = RulesQaFallback.TEXT,
                reason = AiRoutePolicyDecider.FallbackReason.Other("empty question"),
            )
        }

        val context = try {
            contextProvider()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return groundedOrFallback(
                normalizedQuestion,
                AiRoutePolicyDecider.FallbackReason.Other("context snapshot failed: ${t.message}"),
            )
        }

        return when (val decision = AiRoutePolicyDecider.decide(AiRoutePolicies.rulesQaOffline, context)) {
            is AiRoutePolicyDecider.Decision.RunOnDevice -> runOnDevice(normalizedQuestion, decision.route)
            is AiRoutePolicyDecider.Decision.RunCloud -> groundedOrFallback(
                normalizedQuestion,
                AiRoutePolicyDecider.FallbackReason.Other("cloud route not supported"),
            )
            is AiRoutePolicyDecider.Decision.FallBack ->
                groundedOrFallback(normalizedQuestion, decision.reason)
        }
    }

    private suspend fun runOnDevice(question: String, route: VendorRoute): RulesQaResult {
        // The budget lives here, not only inside an answerer, because it is the *only* bound that
        // covers every platform: the Android answerer deadlines its own turns, but the iOS
        // Foundation Models bridge has no timeout of its own, and an answerer that never returns
        // leaves the screen spinning with no way out.
        //
        // What the earlier version got wrong was the fallback, not the timeout: expiring here used
        // to emit RulesQaFallback.TEXT and throw away a corpus hit the deterministic lookup can
        // produce in microseconds. groundedOrFallback re-runs that lookup, so a slow model costs
        // the user the model's phrasing and nothing else.
        val output = try {
            withTimeoutOrNull(AiRoutePolicies.rulesQaOffline.latencyBudget.completeMs) {
                answerer.answer(question, route)
            } ?: return groundedOrFallback(question, AiRoutePolicyDecider.FallbackReason.Timeout)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return groundedOrFallback(
                question,
                AiRoutePolicyDecider.FallbackReason.Other("rules generation failed: ${t.message}"),
            )
        }

        return when (
            val validation = RulesQaResponseValidator.validate(
                output.text,
                output.retrievedPassageIds,
            )
        ) {
            is RulesQaResponseValidator.Result.Valid -> RulesQaResult.Success(
                text = validation.text,
                citations = validation.citedPassageIds.map { id ->
                    RuleCitation(id = id, title = ruleTitleForId(id) ?: id)
                },
                route = AiRoute.OnDevice,
            )
            is RulesQaResponseValidator.Result.Invalid -> {
                logger.w { "Rules Q&A validation failed: ${validation.reason}" }
                grounded(output.retrievedPassages, AiRoutePolicyDecider.FallbackReason.Validation)
                    ?: groundedOrFallback(question, AiRoutePolicyDecider.FallbackReason.Validation)
            }
        }
    }

    /**
     * The retrieval floor, applied to whatever the answerer did *not* manage to use.
     *
     * Every path that gives up on the model lands here rather than on [RulesQaFallback.TEXT]:
     * retrieval is deterministic and cheap, so "the model was slow / threw / would not cite" must
     * never be reported to the user as "the rule could not be found". Only an empty corpus hit
     * reaches the fallback text.
     */
    private suspend fun groundedOrFallback(
        question: String,
        reason: AiRoutePolicyDecider.FallbackReason,
    ): RulesQaResult {
        val retrieved = try {
            lookupTool.lookup(question)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            logger.w { "Rules Q&A lookup failed during fallback: ${t.message}" }
            emptyList()
        }
        grounded(retrieved, reason)?.let { return it }
        logger.w { "Rules Q&A fallback triggered: ${reason.description} | Question: $question" }
        return RulesQaResult.FellBack(text = RulesQaFallback.TEXT, reason = reason)
    }

    /** The top passage as a cited answer, or `null` when nothing was retrieved. */
    private fun grounded(
        passages: List<RulePassage>,
        reason: AiRoutePolicyDecider.FallbackReason,
    ): RulesQaResult.Success? {
        val top = passages.firstOrNull() ?: return null
        return RulesQaResult.Success(
            text = RulesQaGrounding.composeFromPassages(passages),
            citations = listOf(RuleCitation(id = top.id, title = top.title)),
            // Provenance (B11): the text is corpus-composed, not model-authored, even though this
            // is a Success (a complete, correct answer) rather than a FellBack.
            route = AiRoute.Fallback(reason),
        )
    }
}
