package com.example.ondeviceai

data class FoundationRulesQaOutput(
    val text: String,
    val passageIdsCsv: String,
)

interface FoundationRulesQaBridge {
    suspend fun answer(question: String): FoundationRulesQaOutput
}

interface FoundationRuleLookupBridge {
    suspend fun lookupForTool(query: String): String
    suspend fun corpusForEmbedding(): String
}

private class KotlinFoundationRuleLookupBridge(
    private val lookupTool: RuleLookupTool,
) : FoundationRuleLookupBridge {
    override suspend fun lookupForTool(query: String): String = lookupTool.lookup(query)
        .asToolPayload()

    override suspend fun corpusForEmbedding(): String = GeneratedRulePassages.asToolPayload()

    private fun List<RulePassage>.asToolPayload(): String = this
        .joinToString("\n") { passage ->
            listOf(passage.id, passage.title, passage.text).joinToString("\t")
        }
}

private class FoundationRulesQaAnswerer(
    private val bridge: FoundationRulesQaBridge,
) : RulesQaAnswerer {
    override suspend fun answer(question: String, route: VendorRoute): RulesQaModelOutput {
        val output = bridge.answer(question)
        return RulesQaModelOutput(
            text = output.text,
            retrievedPassageIds = output.passageIdsCsv
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty),
        )
    }
}

object FoundationRulesQaBridgeRegistry {
    fun interface Provider {
        fun create(lookupBridge: FoundationRuleLookupBridge): FoundationRulesQaBridge
    }

    var provider: Provider? = null
        private set

    fun register(provider: Provider) {
        this.provider = provider
    }
}

fun registerFoundationRulesQaProvider(
    provider: (FoundationRuleLookupBridge) -> FoundationRulesQaBridge,
) {
    FoundationRulesQaBridgeRegistry.register(
        FoundationRulesQaBridgeRegistry.Provider(provider),
    )
}

actual fun defaultRulesQaAnswerer(lookupTool: RuleLookupTool): RulesQaAnswerer? {
    val bridge = FoundationRulesQaBridgeRegistry.provider?.create(
        KotlinFoundationRuleLookupBridge(lookupTool),
    ) ?: return null
    return FoundationRulesQaAnswerer(bridge)
}
