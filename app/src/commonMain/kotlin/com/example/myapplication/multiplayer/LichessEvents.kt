package com.example.myapplication.multiplayer

import com.example.myapplication.Set as Side
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
internal data class LichessPlayer(
    val id: String? = null,
    val name: String? = null,
    val rating: Int? = null,
    val aiLevel: Int? = null,
) {
    val displayName: String get() = name ?: id ?: "Unknown player"
}

@Serializable
internal data class LichessVariant(val key: String)

@Serializable
internal data class LichessOngoingGame(
    val gameId: String,
    val color: String,
    val opponent: LichessPlayer,
    val variant: LichessVariant? = null,
    val speed: String? = null,
    val rated: Boolean = false,
    val source: String? = null,
) {
    val supported: Boolean
        get() = lichessGameId.matches(gameId) && variant?.key == "standard" && speed == "rapid" &&
            !rated && opponent.aiLevel == null
}

@Serializable
internal data class LichessChallenge(
    val id: String,
    val challenger: LichessPlayer? = null,
    val destUser: LichessPlayer? = null,
    val variant: LichessVariant? = null,
    val speed: String? = null,
    val rated: Boolean = false,
    val timeControl: LichessTimeControl? = null,
) {
    val supported: Boolean
        get() = lichessGameId.matches(id) && variant?.key == "standard" && speed == "rapid" && !rated &&
            timeControl?.limit == 900 && timeControl.increment == 10
}

@Serializable
internal data class LichessTimeControl(val limit: Int? = null, val increment: Int? = null)

@Serializable
internal data class LichessClock(val initial: Long, val increment: Long)

@Serializable
internal data class LichessGameState(
    val moves: String,
    @SerialName("wtime") val whiteTimeMillis: Long,
    @SerialName("btime") val blackTimeMillis: Long,
    @SerialName("winc") val whiteIncrementMillis: Long,
    @SerialName("binc") val blackIncrementMillis: Long,
    val status: String,
    val winner: String? = null,
    @SerialName("wdraw") val whiteDraw: Boolean = false,
    @SerialName("bdraw") val blackDraw: Boolean = false,
) {
    val winnerSide: Side? get() = when (winner) {
        "white" -> Side.WHITE
        "black" -> Side.BLACK
        else -> null
    }
    val isTerminal: Boolean get() = status in terminalStatuses
    val isAborted: Boolean get() = status in abortedStatuses
    val hasResult: Boolean get() = isTerminal && !isAborted && status != "unknownFinish"
}

@Serializable
internal data class LichessGameFull(
    val id: String,
    val variant: LichessVariant,
    val speed: String,
    val rated: Boolean,
    val createdAt: Long,
    val white: LichessPlayer,
    val black: LichessPlayer,
    val initialFen: String,
    val clock: LichessClock? = null,
    val state: LichessGameState,
) {
    val supported: Boolean
        get() = variant.key == "standard" && speed == "rapid" && !rated && initialFen == "startpos" &&
            white.aiLevel == null && black.aiLevel == null && clock?.initial == 900_000L && clock.increment == 10_000L
}

internal sealed interface LichessAccountEvent {
    data object Connected : LichessAccountEvent
    data class GameStarted(val game: LichessOngoingGame) : LichessAccountEvent
    data class GameFinished(val gameId: String) : LichessAccountEvent
    data class ChallengeReceived(val challenge: LichessChallenge) : LichessAccountEvent
    data class ChallengeClosed(val id: String) : LichessAccountEvent
}

internal sealed interface LichessGameEvent {
    data class Full(val game: LichessGameFull) : LichessGameEvent
    data class Changed(val state: LichessGameState) : LichessGameEvent
}

internal data class ChallengeEvent(val id: String? = null, val done: String? = null)

internal val abortedStatuses = setOf("aborted", "noStart")
internal val terminalStatuses = abortedStatuses + setOf(
    "mate", "resign", "stalemate", "timeout", "draw", "outoftime", "cheat", "unknownFinish", "variantEnd",
)

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
