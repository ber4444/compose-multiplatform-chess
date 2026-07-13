package com.example.coachserver

import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.OpeningExplainResponse
import com.example.coachapi.Passage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface Embedder {
    fun embed(text: String): FloatArray
}

interface PassageRepository {
    fun retrieve(embedding: FloatArray, limit: Int = 4): List<Passage>
    fun upsert(passage: Passage, embedding: FloatArray)
}

data class ComposedText(
    val text: String,
    val composerId: String,
)

fun interface TextComposer {
    fun compose(request: OpeningExplainRequest, passages: List<Passage>): ComposedText
}

data class ServerDependencies(
    val embedder: Embedder,
    val passageRepository: PassageRepository,
    val composer: TextComposer,
)

class OpeningService(
    private val dependencies: ServerDependencies,
) {
    suspend fun explain(request: OpeningExplainRequest): OpeningExplainResponse = withContext(Dispatchers.IO) {
        require(request.fen.isNotBlank() && request.fen.length <= MAX_FEN_LENGTH && FEN.matches(request.fen)) {
            "fen is malformed or too long"
        }
        require(request.movesSan.size <= MAX_MOVES) { "movesSan must contain at most $MAX_MOVES entries" }
        require(request.movesSan.all { it.length <= MAX_SAN_LENGTH && SAN.matches(it) }) {
            "movesSan contains a malformed move"
        }
        val eco = request.eco
        require(eco == null || ECO.matches(eco)) { "eco must be a valid ECO code" }
        val locale = request.locale
        require(locale == null || (locale.length <= MAX_LOCALE_LENGTH && LOCALE.matches(locale))) {
            "locale is malformed or too long"
        }

        val embedding = dependencies.embedder.embed(OpeningQueryBuilder.build(request))
        require(embedding.size == EMBEDDING_DIMENSIONS) {
            "embedder returned ${embedding.size} values; expected $EMBEDDING_DIMENSIONS"
        }
        val passages = dependencies.passageRepository.retrieve(embedding, RETRIEVAL_LIMIT)
        val composition = dependencies.composer.compose(request, passages)
        OpeningExplainResponse(
            text = composition.text,
            passages = passages,
            composerId = composition.composerId,
        )
    }

    companion object {
        const val EMBEDDING_DIMENSIONS = 384
        private const val RETRIEVAL_LIMIT = 4
        private const val MAX_MOVES = 20
        private const val MAX_FEN_LENGTH = 128
        private const val MAX_SAN_LENGTH = 16
        private const val MAX_LOCALE_LENGTH = 64
        private val FEN = Regex("[prnbqkPRNBQK1-8/ wbKQkq\\-a-h0-9]+")
        private val SAN = Regex("[KQRBNOa-hx1-8=+#-]+")
        private val ECO = Regex("[A-E][0-9]{2}[a-z]?")
        private val LOCALE = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
    }
}

object OpeningQueryBuilder {
    private const val MAX_OPENING_MOVES = 12

    fun build(request: OpeningExplainRequest): String = buildString {
        request.eco?.takeIf(String::isNotBlank)?.let { append("ECO ").append(it.trim()).append(' ') }
        append("opening after ")
        append(request.movesSan.take(MAX_OPENING_MOVES).joinToString(" ").ifBlank { "the supplied position" })
    }
}
