package com.example.ondeviceai

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AntiRepetitionGuardTest {

    private val metrics = AiInferenceMetrics(
        firstTokenMs = 12L,
        completeMs = 340L,
        tokenCount = 87,
        route = AiRoute.OnDevice,
    )

    private fun final(text: String = "", metrics: AiInferenceMetrics = this.metrics) =
        AiTokenOrFinal.Final(text = text, metrics = metrics)

    private suspend fun guard(
        vararg pieces: AiTokenOrFinal,
        ngramSize: Int? = 4,
        stopSequences: List<String> = emptyList(),
    ): List<AiTokenOrFinal> = flowOf(*pieces)
        .withAntiRepetitionGuard(ngramSize = ngramSize, stopSequences = stopSequences)
        .toList()

    private fun List<AiTokenOrFinal>.text(): String =
        joinToString("") { piece ->
            when (piece) {
                is AiTokenOrFinal.Token -> piece.text
                is AiTokenOrFinal.Final -> piece.text
            }
        }

    @Test
    fun `clean text passes through untouched`() = runTest {
        val pieces = guard(AiTokenOrFinal.Token("Nf3 develops a piece and eyes the center."), final())
        assertEquals("Nf3 develops a piece and eyes the center.", pieces.text())
    }

    /**
     * The one that matters: every local runtime hands over the whole answer as a single Token, so a
     * guard that *drops* the offending piece drops the answer and the coach shows the deterministic
     * fallback instead of the model's (perfectly usable) first sentence.
     */
    @Test
    fun `repetition in a single chunk is truncated not discarded`() = runTest {
        val repetitive = "You grabbed the center with e4. Keep developing your pieces. " +
            "Keep developing your pieces. Keep developing your pieces."
        val pieces = guard(AiTokenOrFinal.Token(repetitive), final())
        assertEquals("You grabbed the center with e4. Keep developing your pieces.", pieces.text())
    }

    @Test
    fun `final always reaches the collector with its metrics untouched`() = runTest {
        val pieces = guard(AiTokenOrFinal.Token("same words again same words again"), final())
        val last = pieces.last()
        assertIs<AiTokenOrFinal.Final>(last)
        assertEquals(metrics, last.metrics)
    }

    @Test
    fun `fallback reason on the final metrics survives the guard`() = runTest {
        val reason = AiRoutePolicyDecider.FallbackReason.Other("Cactus not initialized")
        val pieces = guard(final(metrics = metrics.copy(fallbackReason = reason)))
        val last = pieces.last()
        assertIs<AiTokenOrFinal.Final>(last)
        assertEquals(reason, last.metrics.fallbackReason)
    }

    @Test
    fun `text at and after a stop sequence is cut`() = runTest {
        val pieces = guard(
            AiTokenOrFinal.Token("Solid choice.<end_of_turn>model\n"),
            final(),
            stopSequences = listOf("<end_of_turn>", "<eos>"),
        )
        assertEquals("Solid choice.", pieces.text())
    }

    /** A partial terminator must never render, so the tail is held until it is disambiguated. */
    @Test
    fun `a stop sequence split across chunks never leaks`() = runTest {
        val pieces = guard(
            AiTokenOrFinal.Token("Solid choice."),
            AiTokenOrFinal.Token("<end_of"),
            AiTokenOrFinal.Token("_turn>"),
            final(),
            stopSequences = listOf("<end_of_turn>"),
        )
        assertEquals("Solid choice.", pieces.text())
        assertEquals(1, pieces.count { it is AiTokenOrFinal.Final })
    }

    @Test
    fun `held text that is not a stop sequence is flushed before the final`() = runTest {
        val pieces = guard(
            AiTokenOrFinal.Token("Nf3 is best<"),
            final(),
            stopSequences = listOf("<end_of_turn>"),
        )
        assertEquals("Nf3 is best<", pieces.text())
    }

    @Test
    fun `terminal text is cleaned when a runtime reports it there`() = runTest {
        val pieces = guard(
            final(text = "Nf3 develops.<eos>"),
            stopSequences = listOf("<eos>"),
        )
        assertEquals("Nf3 develops.", pieces.text())
    }

    @Test
    fun `a null ngram size disables the repetition rule`() = runTest {
        val repetitive = "same four words here same four words here"
        val pieces = guard(AiTokenOrFinal.Token(repetitive), final(), ngramSize = null)
        assertEquals(repetitive, pieces.text())
    }

    @Test
    fun `a window that only overlaps itself is not a repeat`() {
        // "a b a" reoccurs here, but only overlapping itself — no window repeats a disjoint earlier
        // one, so the phrase never genuinely reoccurs and the text is left alone.
        assertNull("a b a b a b".repeatedNgramOffset(3))
    }

    @Test
    fun `truncation keeps everything before the repeated ngram`() {
        assertEquals(
            "one two three four five",
            "one two three four five one two three four".truncateAtRepetition(4),
        )
    }
}
