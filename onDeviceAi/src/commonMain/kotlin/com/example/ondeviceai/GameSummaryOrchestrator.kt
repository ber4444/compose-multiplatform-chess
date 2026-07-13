package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow

interface GameSummaryOrchestrator {
    suspend fun summarizeGame(request: GameSummaryRequest): GameSummaryResult
    fun summarizeGameStreaming(request: GameSummaryRequest): Flow<GameSummaryEvent>
}
