package com.example.ondeviceai

import kotlinx.coroutines.flow.Flow

interface AiCoachOrchestrator {
    suspend fun explainMove(request: MoveCoachRequest): MoveCoachResult
    fun explainMoveStreaming(request: MoveCoachRequest): Flow<MoveCoachEvent>
}
