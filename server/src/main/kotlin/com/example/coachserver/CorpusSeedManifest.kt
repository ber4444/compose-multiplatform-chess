package com.example.coachserver

import java.security.MessageDigest

/** Deterministic identity and coverage target for one complete corpus seed. */
data class CorpusSeedManifest(
    val version: String,
    val expectedRowCount: Int,
    val finalSourceId: String,
) {
    companion object {
        fun from(entries: List<SeedMain.CorpusEntry>): CorpusSeedManifest {
            require(entries.isNotEmpty()) { "Cannot seed an empty corpus" }
            val digest = MessageDigest.getInstance("SHA-256")
            entries.forEach { entry ->
                listOf(
                    entry.passage.sourceId,
                    entry.passage.title,
                    entry.passage.text,
                    entry.eco.orEmpty(),
                    entry.moves.orEmpty(),
                ).forEach { value ->
                    val bytes = value.encodeToByteArray()
                    digest.update(bytes.size.toString().encodeToByteArray())
                    digest.update(':'.code.toByte())
                    digest.update(bytes)
                    digest.update('\n'.code.toByte())
                }
            }
            return CorpusSeedManifest(
                version = digest.digest().joinToString("") { "%02x".format(it) },
                expectedRowCount = entries.size,
                finalSourceId = entries.last().passage.sourceId,
            )
        }
    }
}
