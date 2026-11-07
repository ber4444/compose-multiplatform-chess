package com.example.coachserver.saves

import com.example.myapplication.FenConverter
import com.example.myapplication.King
import com.example.myapplication.WinState
import com.example.saveapi.CloudAccount
import com.example.saveapi.CloudResumeGame
import com.example.saveapi.CloudResumeSlot
import com.example.saveapi.CloudSaveError
import com.example.saveapi.CloudSavedGame
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.charset.CharacterCodingException
import java.util.UUID

internal const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
private const val MAX_PAYLOAD_BYTES = 256 * 1024L
private const val RECENT_SIGN_IN_SECONDS = 300L
private val validResults = setOf("1-0", "0-1", "1/2-1/2")
private val quotedRevision = Regex("\"(0|[1-9][0-9]*)\"")

internal fun Route.cloudSaveRoutes(dependencies: CloudSaveDependencies) = route("/v1/saves") {
    val repository = dependencies.repository
    get("/account") {
        dependencies.withAccount(call) { account ->
            call.respond(CloudAccount(account.uid))
        }
    }
    delete("/account") {
        dependencies.withAccount(call, read = false) { account ->
            if (account.authEpochSeconds !in (dependencies.nowEpochSeconds() - RECENT_SIGN_IN_SECONDS)..dependencies.nowEpochSeconds()) {
                call.saveError(HttpStatusCode.Forbidden, "recent_sign_in_required")
                return@withAccount
            }
            repository.deleteAccountData(account.uid)
            dependencies.deleteIdentity(account.uid)
            call.respond(HttpStatusCode.NoContent)
        }
    }
    if (dependencies.historyEnabled) {
        get("/history") {
            dependencies.withAccount(call) { account ->
                val cursor = call.request.queryParameters["cursor"]?.let { token ->
                    CloudHistoryCursor.decode(token) ?: run {
                        call.saveError(HttpStatusCode.BadRequest, "invalid_cursor")
                        return@withAccount
                    }
                }
                call.respond(repository.history(account.uid, cursor))
            }
        }
        route("/history/{id}") {
            get {
                dependencies.withAccount(call) { account ->
                    val id = call.saveId() ?: return@withAccount
                    val game = repository.historyGame(account.uid, id)
                    if (game == null) call.saveError(HttpStatusCode.NotFound, "game_not_found")
                    else call.respond(game)
                }
            }
            put {
                dependencies.withAccount(call, read = false) { account ->
                    val id = call.saveId() ?: return@withAccount
                    val game = call.receiveSave<CloudSavedGame>() ?: return@withAccount
                    if (game.id != id || !game.isValid()) {
                        call.saveError(HttpStatusCode.BadRequest, "invalid_game")
                        return@withAccount
                    }
                    call.respondHistoryWrite(repository.saveHistory(account.uid, game))
                }
            }
            delete {
                dependencies.withAccount(call, read = false) { account ->
                    val id = call.saveId() ?: return@withAccount
                    call.respondHistoryWrite(repository.deleteHistory(account.uid, id))
                }
            }
        }
    }
    if (dependencies.resumeEnabled) {
        route("/current") {
            get {
                dependencies.withAccount(call) { account ->
                    val current = repository.resume(account.uid)
                    call.appendRevision(current.revision)
                    call.respond(CloudResumeSlot(current.game))
                }
            }
            put {
                dependencies.withAccount(call, read = false) { account ->
                    val operation = call.resumeOperation() ?: return@withAccount
                    val game = call.receiveSave<CloudResumeGame>() ?: return@withAccount
                    if (!game.isValid()) {
                        call.saveError(HttpStatusCode.BadRequest, "invalid_snapshot")
                        return@withAccount
                    }
                    call.respondResumeWrite(repository.writeResume(account.uid, operation.revision, operation.id, game))
                }
            }
            delete {
                dependencies.withAccount(call, read = false) { account ->
                    val operation = call.resumeOperation() ?: return@withAccount
                    call.respondResumeWrite(repository.writeResume(account.uid, operation.revision, operation.id, null))
                }
            }
        }
    }
}

private suspend inline fun CloudSaveDependencies.withAccount(
    call: ApplicationCall,
    read: Boolean = true,
    block: (AccountVerification.Verified) -> Unit,
) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val header = call.request.headers[HttpHeaders.Authorization]
    val token = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)
        ?.takeIf { it.length in 1..8192 && it.none(Char::isWhitespace) }
    try {
        when (val account = token?.let { verifier.verify(it) } ?: AccountVerification.Invalid) {
            AccountVerification.Invalid -> {
                call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
                call.saveError(HttpStatusCode.Unauthorized, "invalid_credentials")
            }
            AccountVerification.UnverifiedEmail -> call.saveError(HttpStatusCode.Forbidden, "unverified_email")
            is AccountVerification.Verified -> when {
                !accountRateLimiter.tryAcquire(account.uid) -> call.saveError(HttpStatusCode.TooManyRequests, "rate_limited")
                read && repository.isDeleting(account.uid) -> call.saveError(HttpStatusCode.Conflict, "account_deleting")
                else -> block(account)
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        call.application.environment.log.warn("Cloud save request failed ({})", error.javaClass.simpleName)
        call.saveError(HttpStatusCode.ServiceUnavailable, "saves_unavailable")
    }
}

private suspend fun ApplicationCall.saveId(): String? {
    val id = parameters["id"]?.takeIf(::isSaveId)
    if (id == null) saveError(HttpStatusCode.BadRequest, "invalid_id")
    return id
}

private suspend inline fun <reified T> ApplicationCall.receiveSave(): T? {
    if ((request.contentLength() ?: 0L) > MAX_PAYLOAD_BYTES) {
        saveError(HttpStatusCode.PayloadTooLarge, "request_too_large")
        return null
    }
    val bytes = receiveChannel().readRemaining(MAX_PAYLOAD_BYTES + 1).readByteArray()
    if (bytes.size > MAX_PAYLOAD_BYTES) {
        saveError(HttpStatusCode.PayloadTooLarge, "request_too_large")
        return null
    }
    return try {
        Json.decodeFromString<T>(bytes.decodeToString(throwOnInvalidSequence = true))
    } catch (_: SerializationException) {
        saveError(HttpStatusCode.BadRequest, "invalid_json")
        null
    } catch (_: CharacterCodingException) {
        saveError(HttpStatusCode.BadRequest, "invalid_json")
        null
    }
}

private fun CloudSavedGame.isValid() = schemaVersion == 1 && savedAtEpochMillis >= 0 &&
    result in validResults && white.length in 1..256 && black.length in 1..256 &&
    moveCount >= 0 && pgn.isNotBlank()

private fun CloudResumeGame.isValid(): Boolean {
    if (schemaVersion != 1 || !isSaveId(gameId) || snapshot.winState != WinState.NONE || snapshot.fen.length > 256) {
        return false
    }
    val state = try {
        FenConverter.fenToGameState(snapshot.fen)
    } catch (_: IllegalArgumentException) {
        return false
    }
    return state.piecesWhite.count { it is King } == 1 && state.piecesBlack.count { it is King } == 1 &&
        state.halfmoveClock >= 0 && state.fullmoveNumber >= 1
}

private data class ResumeOperation(val revision: Long, val id: String)

private suspend fun ApplicationCall.resumeOperation(): ResumeOperation? {
    val ifMatch = request.headers[HttpHeaders.IfMatch]
    if (ifMatch == null) {
        saveError(HttpStatusCode(428, "Precondition Required"), "revision_required")
        return null
    }
    val revision = quotedRevision.matchEntire(ifMatch)?.groupValues?.get(1)?.toLongOrNull()
    val operationId = request.headers[IDEMPOTENCY_KEY_HEADER]
    val parsedId = operationId?.takeIf { it.length == 36 }?.let {
        try {
            UUID.fromString(it).toString()
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    if (revision == null || parsedId == null || parsedId != operationId) {
        saveError(HttpStatusCode.BadRequest, "invalid_precondition")
        return null
    }
    return ResumeOperation(revision, parsedId)
}

private fun ApplicationCall.appendRevision(revision: Long) {
    response.headers.append(HttpHeaders.ETag, "\"$revision\"")
}

private suspend fun ApplicationCall.respondHistoryWrite(result: HistoryWrite) = when (result) {
    HistoryWrite.Saved -> respond(HttpStatusCode.NoContent)
    HistoryWrite.Conflict -> saveError(HttpStatusCode.Conflict, "game_conflict")
    HistoryWrite.Deleted -> saveError(HttpStatusCode.Conflict, "game_deleted")
    HistoryWrite.AccountDeleting -> saveError(HttpStatusCode.Conflict, "account_deleting")
}

private suspend fun ApplicationCall.respondResumeWrite(result: ResumeWrite) = when (result) {
    is ResumeWrite.Saved -> {
        appendRevision(result.revision)
        respond(HttpStatusCode.NoContent)
    }
    ResumeWrite.Conflict -> saveError(HttpStatusCode.Conflict, "operation_conflict")
    ResumeWrite.Stale -> saveError(HttpStatusCode.PreconditionFailed, "stale_revision")
    ResumeWrite.AccountDeleting -> saveError(HttpStatusCode.Conflict, "account_deleting")
}

private suspend fun ApplicationCall.saveError(status: HttpStatusCode, code: String) = respond(status, CloudSaveError(code))
