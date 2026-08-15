package com.example.evals

import kotlin.io.path.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the device-JSONL ingest, which nothing else can: the producer is `AndroidBenchRunner` on a
 * phone, so a schema change there is invisible to CI until someone spends 13 minutes re-running a
 * benchmark.
 *
 * The fixture rows are **verbatim** from the 2026-08-15 Pixel 10 Pro XL run — one validator-accepted,
 * one vetoed, one that never produced text because AICore refuses to generate in the background.
 * Hand-written rows were tried first and were worse than useless: they asserted agreement between
 * this scorer and a device verdict that had been made up to match, which is the one thing this test
 * exists to check.
 */
class DeviceRunScorerTest {

    private val cases = GoldenCaseLoader.load(Path("golden/candidates.json"))
    private val fixture = Path("src/test/resources/device-run-fixture.jsonl")

    private fun report(lines: List<String> = fixture.readLines()): DeviceRunReport {
        val file = createTempFile(suffix = ".jsonl")
        file.writeText(lines.joinToString("\n"))
        return scoreDeviceRun(DeviceRunLoader.load(file), cases)
    }

    /**
     * The load-bearing one. The device already ran this validator; a disagreement means the row's
     * recorded facts no longer rebuild the request it validated against, so every quality number is
     * scored against a different prompt than the one that shipped.
     */
    @Test
    fun `reproduces the device's own validator verdict`() {
        val report = report()
        assertTrue(report.disagreements.isEmpty(), "disagreed on ${report.disagreements}")
        assertEquals(2, report.scored)
        assertEquals(1, report.modelGrounded)
    }

    /**
     * Both rows that carry text carry a baseline — and one of them is rejected by a rule that
     * cannot mean here what it says: the deterministic line *is* the prompt's baseline sentence, so
     * it echoes the prompt by construction. On the full 100-case run that rule accounts for 17 of
     * the 28 baseline rejections, and reading `72/100` as a verdict on `DeterministicCoach` is the
     * mistake this breakdown exists to prevent.
     */
    @Test
    fun `scores the deterministic baseline as its own column, with the rule that rejected it`() {
        val report = report()
        assertEquals(2, report.baselineScored)
        assertEquals(1, report.baselineGrounded)
        assertEquals(mapOf("echoed the prompt instead of answering it" to 1), report.baselineRejections)
    }

    @Test
    fun `a row that never produced text is not counted as ungrounded`() {
        val report = report()
        assertEquals(1, report.unusable.values.sum())
        assertTrue(
            report.unusable.keys.single().contains("Background usage is blocked"),
            "expected the AICore foreground error, got ${report.unusable.keys}",
        )
    }

    /** A placeholder run must never reach a quality column, however plausible its text reads. */
    @Test
    fun `placeholder rows are excluded from scoring entirely`() {
        val degraded = fixture.readLines().map {
            it.replace("\"factsPopulated\":true", "\"factsPopulated\":false")
        }
        val report = report(degraded)
        assertEquals(0, report.scored)
        assertEquals(0, report.baselineScored)
        assertEquals(3, report.placeholderRows)
    }

    @Test
    fun `unknown columns from a newer device build do not fail the load`() {
        val extended = fixture.readLines().map { it.dropLast(1) + ""","addedByALaterBuild":42}""" }
        assertEquals(2, report(extended).scored)
    }
}
