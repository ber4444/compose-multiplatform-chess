package com.example.evals

import com.example.coachapi.CloudDiagnostics
import com.example.coachapi.CorpusDiagnostics
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudDiagnosticsEvalTest {

    private fun fixtureDiagnostics(
        retrievedPassageIds: List<String> = listOf("lichess-c20")
    ) = CloudDiagnostics(
        releaseVersion = "test-release",
        corpus = CorpusDiagnostics(ready = true),
        retrievedPassageIds = retrievedPassageIds,
        composerId = "test-composer",
        finishReason = "completed",
        latencyMs = 100
    )

    @Test
    fun matchingDiagnosticsPass() {
        assertTrue(EvalScorer.scoreDiagnostics("C20", fixtureDiagnostics()).retrievalCorrect)
    }

    @Test
    fun wrongDiagnosticRetrievalFailsDespiteGroundedProse() {
        assertFalse(EvalScorer.scoreDiagnostics("C20", fixtureDiagnostics(retrievedPassageIds = listOf("eval-E06"))).retrievalCorrect)
    }
}
