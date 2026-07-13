package com.example.coachserver

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt

class OnnxMiniLmEmbedder(
    modelPath: Path,
    vocabPath: Path,
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Embedder, AutoCloseable {
    private val session: OrtSession = environment.createSession(modelPath.toString(), OrtSession.SessionOptions())
    private val tokenizer = WordPieceTokenizer(Files.readAllLines(vocabPath))

    override fun embed(text: String): FloatArray {
        val tokenIds = tokenizer.encode(text, MAX_TOKENS)
        val attentionMask = LongArray(tokenIds.size) { 1L }
        val tokenTypes = LongArray(tokenIds.size)
        OnnxTensor.createTensor(environment, arrayOf(tokenIds)).use { idsTensor ->
            OnnxTensor.createTensor(environment, arrayOf(attentionMask)).use { maskTensor ->
                OnnxTensor.createTensor(environment, arrayOf(tokenTypes)).use { typeTensor ->
                    val inputs = buildMap {
                        put("input_ids", idsTensor)
                        put("attention_mask", maskTensor)
                        if ("token_type_ids" in session.inputNames) put("token_type_ids", typeTensor)
                    }
                    session.run(inputs).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val hidden = result[0].value as Array<Array<FloatArray>>
                        return meanPoolAndNormalize(hidden[0], attentionMask)
                    }
                }
            }
        }
    }

    override fun close() {
        session.close()
    }

    private fun meanPoolAndNormalize(tokens: Array<FloatArray>, mask: LongArray): FloatArray {
        val result = FloatArray(OpeningService.EMBEDDING_DIMENSIONS)
        var count = 0
        tokens.forEachIndexed { index, token ->
            if (mask.getOrElse(index) { 0L } == 0L) return@forEachIndexed
            require(token.size == result.size) { "MiniLM output must have ${result.size} dimensions" }
            token.indices.forEach { dimension -> result[dimension] += token[dimension] }
            count++
        }
        require(count > 0) { "MiniLM returned no token embeddings" }
        result.indices.forEach { result[it] /= count }
        val norm = sqrt(result.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: 1f
        result.indices.forEach { result[it] /= norm }
        return result
    }

    private class WordPieceTokenizer(vocabulary: List<String>) {
        private val tokenIds = vocabulary.withIndex().associate { it.value to it.index.toLong() }
        private val unknown = tokenIds["[UNK]"] ?: 100L
        private val cls = tokenIds["[CLS]"] ?: 101L
        private val sep = tokenIds["[SEP]"] ?: 102L

        fun encode(text: String, maxTokens: Int): LongArray {
            val pieces = mutableListOf(cls)
            basicTokens(text).forEach { token ->
                if (pieces.size >= maxTokens - 1) return@forEach
                wordPieces(token).forEach { piece ->
                    if (pieces.size < maxTokens - 1) pieces += piece
                }
            }
            pieces += sep
            return pieces.toLongArray()
        }

        private fun basicTokens(text: String): List<String> = text.lowercase()
            .replace(Regex("([.,!?;:()\\[\\]{}\\-/])"), " $1 ")
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)

        private fun wordPieces(token: String): List<Long> {
            tokenIds[token]?.let { return listOf(it) }
            val result = mutableListOf<Long>()
            var start = 0
            while (start < token.length) {
                var end = token.length
                var match: Long? = null
                while (start < end) {
                    val candidate = (if (start == 0) "" else "##") + token.substring(start, end)
                    match = tokenIds[candidate]
                    if (match != null) break
                    end--
                }
                if (match == null) return listOf(unknown)
                result += match
                start = end
            }
            return result
        }
    }

    companion object {
        private const val MAX_TOKENS = 128
    }
}
