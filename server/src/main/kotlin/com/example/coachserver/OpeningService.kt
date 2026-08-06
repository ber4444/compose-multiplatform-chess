package com.example.coachserver

import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.OpeningExplainResponse
import com.example.coachapi.Passage
import com.example.coachapi.CloudDiagnostics
import com.example.coachapi.CorpusDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface Embedder {
    fun embed(text: String): FloatArray
}

/**
 * What one retrieval returned, plus the ECO the *server* resolved from the move prefix.
 *
 * [resolvedEco] is not an echo of the request: both clients send `eco = null` (they have no ECO
 * book), so the server identifying the opening itself is the only way an ECO ever reaches the
 * composers. `null` means the moves matched nothing in the book.
 */
data class RetrievalResult(
    val passages: List<Passage>,
    val resolvedEco: String?,
)

interface PassageRepository {
    /**
     * Retrieves up to [limit] passages for a position.
     *
     * [movesSan] is the load-bearing argument, not [embedding]. An opening is a property of its
     * move prefix, so implementations must resolve the line by *exact longest-prefix match* over
     * the corpus move index first, and use the embedding only to fill the remaining slots. A
     * 384-dimension MiniLM vector cannot tell C00 from E00 — measured against the live corpus,
     * embedding-only retrieval answered a French Defence position with four Catalan passages and a
     * Sicilian position with the English Opening.
     *
     * [eco] is a caller-supplied hint used only when the move prefix matches nothing.
     */
    fun retrieve(
        embedding: FloatArray,
        limit: Int = 4,
        movesSan: List<String> = emptyList(),
        eco: String? = null,
    ): RetrievalResult

    fun upsert(passage: Passage, embedding: FloatArray, eco: String? = null, moves: String? = null)
}

data class ComposedText(
    val text: String,
    val composerId: String,
    val finishReason: String = "completed",
    val completionTokens: Int? = null,
    val rawProviderOutput: String? = null,
)

fun interface TextComposer {
    fun compose(request: OpeningExplainRequest, passages: List<Passage>): ComposedText
}

data class ServerDependencies(
    val embedder: Embedder,
    val passageRepository: PassageRepository,
    val composer: TextComposer,
    val releaseVersion: String = "unknown",
    val corpusStatusReader: CorpusStatusReader = CorpusStatusReader { CorpusDiagnostics(ready = false) },
)

class OpeningService(
    private val dependencies: ServerDependencies,
) {
    suspend fun explain(request: OpeningExplainRequest): OpeningExplainResponse = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
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
        val retrieval = dependencies.passageRepository.retrieve(
            embedding = embedding,
            limit = RETRIEVAL_LIMIT,
            movesSan = request.movesSan,
            eco = eco,
        )
        val passages = retrieval.passages
        // Hand the composers the ECO the book resolved. Clients always send null, so without this
        // the LLM prompt's "ECO:" line reads "unknown" on every single request.
        val composition = dependencies.composer.compose(
            request.copy(eco = retrieval.resolvedEco ?: eco),
            passages,
        )
        OpeningExplainResponse(
            text = composition.text,
            passages = passages,
            composerId = composition.composerId,
            diagnostics = CloudDiagnostics(
                releaseVersion = dependencies.releaseVersion,
                corpus = dependencies.corpusStatusReader.readOrUnavailable(),
                retrievedPassageIds = passages.map(Passage::sourceId),
                composerId = composition.composerId,
                finishReason = composition.finishReason,
                latencyMs = ((System.nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0),
                completionTokens = composition.completionTokens,
                rawProviderOutput = composition.rawProviderOutput,
            ),
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

    /**
     * Builds the *embedding* query only. The ECO code is deliberately **not** concatenated here:
     * as one token in a 384-dimension vector it was outvoted by everything else, and measurably
     * worse than absent — a request carrying `eco = "C00"` retrieved four E00/E06 Catalan passages,
     * the vector having latched onto the wrong volume letter. ECO is a structured key, so it is
     * passed to `PassageRepository.retrieve` as a filter instead of being blended into prose.
     */
    fun build(request: OpeningExplainRequest): String = buildString {
        append("opening after ")
        append(request.movesSan.take(MAX_OPENING_MOVES).joinToString(" ").ifBlank { "the supplied position" })
    }
}
