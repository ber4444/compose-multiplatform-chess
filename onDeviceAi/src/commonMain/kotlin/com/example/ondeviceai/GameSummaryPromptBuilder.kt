package com.example.ondeviceai

internal object GameSummaryPromptBuilder {

    // System instruction for the model. We want a short summary of the biggest mistake.
    private const val SYSTEM_PROMPT = """You are a chess coach. Analyze this PGN and identify the single greatest mistake made by the player. Be concise (2-3 sentences)."""

    fun build(request: GameSummaryRequest): AiGenerationRequest {
        val userPrompt = """
            PGN:
            ${request.pgn}
            
            Identify the greatest mistake:
        """.trimIndent()
        
        return AiGenerationRequest(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = userPrompt,
            maxOutputTokens = 150,
            temperature = 0.2
        )
    }
}
