package com.example.ondeviceai

import kotlin.math.ln

data class RulePassage(
    val id: String,
    val title: String,
    val text: String,
)

fun interface RuleLookupTool {
    suspend fun lookup(query: String): List<RulePassage>
}

fun createBundledRuleLookupTool(): RuleLookupTool = BundledRuleLookupTool()

/**
 * Human-readable title for a bundled corpus id, or `null` if the id is not from this corpus.
 *
 * Titles are resolved from the corpus at render time rather than carried alongside the id through
 * `RulesQaModelOutput` → `RulesQaResult` → the UI. The corpus is already the single source of truth
 * for them, and a copy travelling beside the id is a copy that can go stale — the same reason
 * `AiContextSnapshot.isDeviceModelAvailable` is a derived getter and not a stored field.
 *
 * It also keeps iOS out of it: that bridge hands back a CSV of ids and has no passages to thread.
 */
internal fun rulePassageForId(id: String): RulePassage? =
    GeneratedRulePassages.firstOrNull { it.id == id }

internal fun ruleTitleForId(id: String): String? = rulePassageForId(id)?.title

/**
 * Small, deterministic BM25 scan over the generated bundled corpus.
 *
 * A separate sentence-embedding model would add a material binary and memory cost on top of the
 * generation runtime. The milestone explicitly permits keyword/BM25 when no compact embedding
 * model fits the size budget, so v1 keeps every lookup local and dependency-free.
 */
class BundledRuleLookupTool(
    private val passages: List<RulePassage> = GeneratedRulePassages,
    private val maxResults: Int = 4,
) : RuleLookupTool {

    init {
        require(maxResults > 0) { "maxResults must be positive" }
    }

    override suspend fun lookup(query: String): List<RulePassage> {
        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return emptyList()

        val documents = passages.map { passage ->
            // A title is a compact relevance label, so weight it without maintaining a second
            // platform-specific index.
            tokenize("${passage.title} ${passage.title} ${passage.title} ${passage.text}")
        }
        val averageLength = documents.map { it.size }.average().coerceAtLeast(1.0)
        val documentFrequency = queryTerms.associateWith { term ->
            documents.count { document -> term in document }
        }

        return passages.indices
            .map { index ->
                val document = documents[index]
                val counts = document.groupingBy { it }.eachCount()
                val score = queryTerms.distinct().sumOf { term ->
                    val frequency = counts[term]?.toDouble() ?: return@sumOf 0.0
                    val containing = documentFrequency.getValue(term).toDouble()
                    val inverseDocumentFrequency = ln(
                        1.0 + (passages.size - containing + 0.5) / (containing + 0.5),
                    )
                    val lengthNormalization = frequency + K1 * (
                        1.0 - B + B * document.size / averageLength
                    )
                    inverseDocumentFrequency * frequency * (K1 + 1.0) / lengthNormalization
                }
                passages[index] to score
            }
            .filter { (_, score) -> score > 0.0 }
            .sortedWith(compareByDescending<Pair<RulePassage, Double>> { it.second }.thenBy { it.first.id })
            .take(maxResults)
            .map { it.first }
    }

    private fun tokenize(value: String): List<String> = WORD.findAll(value.lowercase())
        .map { match -> match.value }
        .filter { term -> (term.length > 1 || term.all { it.isDigit() }) && term !in STOP_WORDS }
        .map(::normalize)
        .toList()

    private fun normalize(term: String): String = when {
        term.startsWith("castl") -> "castling"
        term.startsWith("promot") -> "promotion"
        term.startsWith("repetit") -> "repetition"
        term.startsWith("captur") -> "capture"
        term.startsWith("attack") -> "attack"
        term.startsWith("touch") -> "touch"
        term.startsWith("draw") -> "draw"
        term == "2" -> "two"
        term.endsWith("ies") && term.length > 4 -> term.dropLast(3) + "y"
        term.endsWith("s") && term.length > 3 -> term.dropLast(1)
        else -> term
    }

    private companion object {
        const val K1 = 1.2
        const val B = 0.75
        val WORD = Regex("[a-z0-9]+")
        val STOP_WORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does", "for",
            "from", "how", "i", "in", "is", "it", "of", "on", "or", "the", "then", "to",
            "what", "when", "with",
        )
    }
}
