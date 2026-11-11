package com.example.coachserver.saves

import com.example.coachapi.Passage
import com.example.coachserver.Embedder
import com.example.coachserver.PassageRepository
import com.example.coachserver.RetrievalResult
import com.example.coachserver.ServerDependencies
import com.example.coachserver.TemplateComposer
import com.example.coachserver.openingCoachModule
import com.example.myapplication.GameSnapshot
import com.example.myapplication.Set
import com.example.saveapi.CloudAccount
import com.example.saveapi.CloudHistoryPage
import com.example.saveapi.CloudResumeGame
import com.example.saveapi.CloudResumeSlot
import com.example.saveapi.CloudSavedGame
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeByteArray
import io.ktor.http.content.OutgoingContent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudSaveRoutesTest {
    private val repository = TestSaveRepository()
    private val clock = 1_750_000_000L
    private val deletedUsers = mutableListOf<String>()

    private val dependencies = CloudSaveDependencies(
        repository,
        AccountVerifier { token ->
            when (token) {
                "unverified" -> AccountVerification.UnverifiedEmail
                "old" -> AccountVerification.Verified("alice", clock - 301)
                "alice", "bob" -> AccountVerification.Verified(token, clock)
                else -> AccountVerification.Invalid
            }
        },
        deleteIdentity = { deletedUsers += it },
        resumeEnabled = true,
        nowEpochSeconds = { clock },
    )

    @Test
    fun verifiesIdentityAndAllowsHostedClientPreflight() = testApplication {
        application { openingCoachModule(coachDependencies(), allowedOrigins = setOf("chess.example"), cloudSaves = dependencies) }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        assertEquals(HttpStatusCode.Unauthorized, jsonClient.get("/v1/saves/account").status)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient.get("/v1/saves/account") { bearerAuth("forged") }.status)
        assertEquals(HttpStatusCode.Forbidden, jsonClient.get("/v1/saves/account") { bearerAuth("unverified") }.status)
        val account = jsonClient.get("/v1/saves/account?uid=bob") { bearerAuth("alice") }
        assertEquals(CloudAccount("alice"), account.body<CloudAccount>())
        assertEquals("no-store", account.headers[HttpHeaders.CacheControl])

        val preflight = jsonClient.options("/v1/saves/current") {
            header(HttpHeaders.Origin, "https://chess.example")
            header(HttpHeaders.AccessControlRequestMethod, "PUT")
            header(HttpHeaders.AccessControlRequestHeaders, "authorization, content-type, if-match, idempotency-key")
        }
        assertEquals(HttpStatusCode.OK, preflight.status)
        assertEquals("https://chess.example", preflight.headers[HttpHeaders.AccessControlAllowOrigin])
        val resume = jsonClient.get("/v1/saves/current") {
            header(HttpHeaders.Origin, "https://chess.example")
            bearerAuth("alice")
        }
        assertTrue(resume.headers[HttpHeaders.AccessControlExposeHeaders].orEmpty().contains("ETag", ignoreCase = true))
    }

    @Test
    fun gatesSaveRoutesSeparatelyFromCoach() = testApplication {
        application { openingCoachModule(coachDependencies()) }
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/saves/account") { bearerAuth("alice") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun historyIsOwnerScopedRetryableAndDeletionDoesNotResurrect() = testApplication {
        application { openingCoachModule(coachDependencies(), cloudSaves = dependencies) }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        val record = sampleGame()
        suspend fun save(game: CloudSavedGame) = jsonClient.put("/v1/saves/history/${game.id}") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(game)
        }
        assertEquals(HttpStatusCode.NoContent, save(record).status)
        assertEquals(HttpStatusCode.NoContent, save(record).status)
        assertEquals(HttpStatusCode.Conflict, save(record.copy(pgn = "different PGN")).status)
        assertEquals(HttpStatusCode.NotFound, jsonClient.get("/v1/saves/history/game-1") { bearerAuth("bob") }.status)
        val list = jsonClient.get("/v1/saves/history") { bearerAuth("alice") }.body<CloudHistoryPage>()
        assertEquals(listOf("game-1"), list.items.map { it.id })
        assertEquals(record, jsonClient.get("/v1/saves/history/game-1") { bearerAuth("alice") }.body<CloudSavedGame>())
        assertEquals(HttpStatusCode.NoContent, jsonClient.delete("/v1/saves/history/game-1") { bearerAuth("alice") }.status)
        assertEquals(HttpStatusCode.Conflict, save(record).status)
        assertEquals(HttpStatusCode.NotFound, jsonClient.get("/v1/saves/history/game-1") { bearerAuth("alice") }.status)
    }

    @Test
    fun rejectsOversizedStreamingWrites() = testApplication {
        application { openingCoachModule(coachDependencies(), cloudSaves = dependencies) }
        val response = client.put("/v1/saves/history/game-1") {
            bearerAuth("alice")
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.Json

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeByteArray(ByteArray(256 * 1024 + 1) { ' '.code.toByte() })
                }
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun resumeUsesPreconditionsAndOldReceiptsCannotRestoreAClearedSlot() = testApplication {
        application { openingCoachModule(coachDependencies(), cloudSaves = dependencies) }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        val firstOperation = UUID.randomUUID().toString()
        val secondOperation = UUID.randomUUID().toString()
        val resume = sampleResume()
        suspend fun putResume(revision: String, operation: String, game: CloudResumeGame = resume) =
            jsonClient.put("/v1/saves/current") {
                bearerAuth("alice")
                header(HttpHeaders.IfMatch, revision)
                header("Idempotency-Key", operation)
                contentType(ContentType.Application.Json)
                setBody(game)
            }
        val initial = jsonClient.get("/v1/saves/current") { bearerAuth("alice") }
        assertEquals("\"0\"", initial.headers[HttpHeaders.ETag])
        assertEquals(CloudResumeSlot(), initial.body<CloudResumeSlot>())
        val first = putResume("\"0\"", firstOperation)
        assertEquals(HttpStatusCode.NoContent, first.status)
        assertEquals("\"1\"", first.headers[HttpHeaders.ETag])
        assertEquals(HttpStatusCode.PreconditionFailed, putResume("\"0\"", secondOperation).status)
        assertEquals(HttpStatusCode.Conflict, putResume("\"0\"", firstOperation, resume.copy(playerSide = Set.WHITE)).status)
        val current = jsonClient.get("/v1/saves/current") { bearerAuth("alice") }
        assertEquals(resume, current.body<CloudResumeSlot>().game)
        val clear = jsonClient.delete("/v1/saves/current") {
            bearerAuth("alice")
            header(HttpHeaders.IfMatch, "\"1\"")
            header("Idempotency-Key", secondOperation)
        }
        assertEquals("\"2\"", clear.headers[HttpHeaders.ETag])
        assertEquals("\"1\"", putResume("\"0\"", firstOperation).headers[HttpHeaders.ETag])
        assertEquals(HttpStatusCode.PreconditionFailed, putResume("\"1\"", UUID.randomUUID().toString()).status)
        val empty = jsonClient.get("/v1/saves/current") { bearerAuth("alice") }
        assertEquals("\"2\"", empty.headers[HttpHeaders.ETag])
        assertEquals(CloudResumeSlot(), empty.body<CloudResumeSlot>())
    }

    @Test
    fun deletionRequiresRecentSignInAndKeepsOtherAccounts() = testApplication {
        repository.saveHistory("alice", sampleGame())
        repository.saveHistory("bob", sampleGame())
        application { openingCoachModule(coachDependencies(), cloudSaves = dependencies) }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }
        assertEquals(HttpStatusCode.Forbidden, jsonClient.delete("/v1/saves/account") { bearerAuth("old") }.status)
        assertEquals(HttpStatusCode.NoContent, jsonClient.delete("/v1/saves/account") { bearerAuth("alice") }.status)
        assertEquals(listOf("alice"), deletedUsers)
        assertEquals(HttpStatusCode.Conflict, jsonClient.get("/v1/saves/history/game-1") { bearerAuth("alice") }.status)
        assertEquals(HttpStatusCode.OK, jsonClient.get("/v1/saves/history/game-1") { bearerAuth("bob") }.status)
    }

    private fun coachDependencies() = ServerDependencies(
        embedder = Embedder { FloatArray(384) },
        passageRepository = object : PassageRepository {
            override fun retrieve(embedding: FloatArray, limit: Int, movesSan: List<String>, eco: String?) =
                RetrievalResult(emptyList(), eco)

            override fun upsert(passage: Passage, embedding: FloatArray, eco: String?, moves: String?) = Unit
        },
        composer = TemplateComposer(),
    )
}

internal fun sampleGame(id: String = "game-1") = CloudSavedGame(id, 42L, "1-0", "White", "Black", 4, "1. e4 1-0")
internal fun sampleResume() = CloudResumeGame(
    "resume-1",
    Set.BLACK,
    GameSnapshot("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"),
)
