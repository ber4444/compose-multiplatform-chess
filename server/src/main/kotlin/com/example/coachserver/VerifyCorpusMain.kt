package com.example.coachserver

import java.nio.file.Path

object VerifyCorpusMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val environment = System.getenv()
        val databaseUrl = requireNotNull(environment["DATABASE_URL"]?.takeIf(String::isNotBlank)) {
            "DATABASE_URL must be set"
        }
        val corpusDirectory = Path.of(environment["COACH_CORPUS_DIR"] ?: "corpus")
        val manifest = CorpusSeedManifest.from(SeedMain.loadCorpus(corpusDirectory))
        createDataSource(databaseUrl).use { dataSource ->
            val verified = verifyCorpus(dataSource, manifest)
            println(
                "Corpus verified: version=${verified.seedVersion} rows=${verified.rowCount} " +
                    "finalSourceId=${verified.finalSourceId} corpus=$corpusDirectory",
            )
        }
    }
}
