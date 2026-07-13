package com.example.evals

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class GoldenCase(
    val id: String,
    val fen: String,
    val bestMoveUci: String,
    val tags: List<String>,
    val eco: String? = null,
    val movesSan: List<String> = emptyList(),
    val expectedConcepts: List<String> = emptyList(),
)

object GoldenCaseLoader {
    private val json = Json { ignoreUnknownKeys = false }

    fun load(path: Path): List<GoldenCase> = json.decodeFromString(
        ListSerializer(GoldenCase.serializer()),
        Files.readString(path),
    )
}
