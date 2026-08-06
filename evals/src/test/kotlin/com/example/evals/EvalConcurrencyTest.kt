package com.example.evals

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two properties [mapCasesConcurrently] exists for. Both are invisible in the scorecard when
 * broken: out-of-order results still produce a plausible-looking row, and an unbounded fan-out only
 * shows up as a burst of provider errors that read as quality failures.
 */
class EvalConcurrencyTest {

    private fun case(index: Int) = GoldenCase(
        id = "case-$index",
        fen = "8/8/8/8/8/8/8/8 w - - 0 1",
        bestMoveUci = "e2e4",
        tags = emptyList(),
    )

    @Test
    fun `results come back in case order even when completion order is reversed`() = runBlocking {
        val cases = (0 until 12).map(::case)

        // The last case finishes first, the first finishes last.
        val results = mapCasesConcurrently(cases, concurrency = 12) { c ->
            val index = c.id.substringAfter('-').toInt()
            delay((cases.size - index) * 5L)
            index
        }

        assertEquals(cases.map(GoldenCase::id), results.map { it.first.id })
        assertEquals((0 until 12).toList(), results.map { it.second })
    }

    @Test
    fun `concurrency stays within the configured bound`() = runBlocking {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()

        mapCasesConcurrently((0 until 40).map(::case), concurrency = 4) {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { previous -> maxOf(previous, now) }
            delay(10)
            inFlight.decrementAndGet()
        }

        assertTrue(peak.get() <= 4, "peaked at ${peak.get()} concurrent calls against a bound of 4")
        assertTrue(peak.get() > 1, "the calls did not actually run concurrently")
    }
}
