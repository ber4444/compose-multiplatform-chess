package com.example.evals

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RulesQaDeviceRunScorerTest {
    @Test
    fun `reports out of corpus retrieval and invented model citations independently`() {
        val path = Files.createTempFile("rules-qa", ".jsonl")
        try {
            Files.writeString(path, """
                {"question":"Only kings?","expectedPassageId":"draw-dead-position","retrievedPassageId":"draw-dead-position","modelAnswer":"Draw [draw-dead-position].","modelPassageIds":["draw-dead-position"],"citedPassageIds":["draw-dead-position"],"kind":"Model","elapsedMs":1000}
                {"question":"What rating?","expectedPassageId":null,"retrievedPassageId":"initial-position","modelAnswer":"Unverified [ranking].","modelPassageIds":["initial-position"],"citedPassageIds":["ranking"],"kind":"Grounded","fallbackReason":"invalid citation","elapsedMs":20000}
            """.trimIndent())
            val report = scoreRulesQaDeviceRun(path)
            assertEquals(1, report.inCorpusHits)
            assertEquals(listOf("What rating?"), report.outOfCorpusHits)
            assertEquals(listOf("What rating?"), report.inventedCitations)
            assertEquals(1, report.rescued)
            assertEquals(20000L, report.p95Ms)
        } finally {
            Files.delete(path)
        }
    }
}
