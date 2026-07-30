package com.example.evals

import com.example.coachapi.ChatStreamEvent
import com.example.coachapi.ChatTurn
import com.example.coachapi.PositionChatRequest

/**
 * One scripted turn of a multi-turn chat eval. [userMessage] is the player's question; the scorer
 * checks the accumulated assistant reply against [expectedConcepts] (the "no grounding drift"
 * guarantee — later turns must still reference the pinned position's concepts).
 */
data class ChatTurnFixture(
    val userMessage: String,
    val expectedConcepts: List<String>,
)

/**
 * A scripted multi-turn conversation about a single position. Built deterministically from a golden
 * case so the eval stays offline (AUTOMATED): two turns per case, both anchored to the case's
 * expected concepts. The server re-pins retrieval every turn, so this also exercises the
 * "grounding never drops out of context" contract.
 */
data class ChatTranscript(
    val case: GoldenCase,
    val turns: List<ChatTurnFixture>,
)

/** Builds scripted two-turn transcripts for every opening case. */
internal fun List<GoldenCase>.toChatTranscripts(): List<ChatTranscript> = map { case ->
    val concepts = case.expectedConcepts.takeIf { it.isNotEmpty() } ?: listOf("development", "center")
    ChatTranscript(
        case = case,
        turns = listOf(
            ChatTurnFixture(
                userMessage = "What should I know about this position?",
                expectedConcepts = concepts,
            ),
            ChatTurnFixture(
                // A later, more specific question — must still cite the pinned position's concepts.
                userMessage = "And what is the key idea going forward?",
                expectedConcepts = concepts,
            ),
        ),
    )
}

/** Builds the request for [turnIndex], carrying the prior assistant/user turns as bounded history. */
internal fun ChatTranscript.toRequest(turnIndex: Int): PositionChatRequest {
    val history = turns.take(turnIndex).map { turn ->
        // The scripted history pretends each prior turn got a short grounded reply; only the
        // userMessage of the current turn is the live question. Grounding concepts are re-checked
        // per turn, so the fake prior content doesn't mask a drift regression.
        listOf(
            ChatTurn("user", turn.userMessage),
            ChatTurn("assistant", turn.expectedConcepts.joinToString(separator = " ") { "$it [eval-${case.id}]." }),
        )
    }.flatten()
    return PositionChatRequest(
        fen = case.fen,
        movesSan = case.movesSan,
        eco = case.eco,
        locale = "en-US",
        history = history,
        userMessage = turns[turnIndex].userMessage,
    )
}

/** Accumulates the streamed token/fallback events for one turn into the final assistant text. */
internal fun List<ChatStreamEvent>.accumulateTurnText(): String {
    val tokens = filter { it.type == ChatStreamEvent.TYPE_TOKEN }.joinToString("") { it.text ?: "" }
    val fallback = firstOrNull { it.type == ChatStreamEvent.TYPE_FALLBACK }?.text
    return (fallback ?: tokens).trim()
}

/** True if the stream's terminal event marks the turn as a validation fallback. */
internal fun List<ChatStreamEvent>.fellBack(): Boolean =
    any { it.type == ChatStreamEvent.TYPE_FALLBACK }
