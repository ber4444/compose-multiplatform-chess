package com.example.myapplication.multiplayer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LichessBoardClientTest {
    @Test
    fun skipsKeepAlivesAndUnknownEvents() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/stream/event", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            respond(
                """
                {"type":"futureEvent"}

                {"type":"challengeCanceled","challenge":{"id":"game0001"}}
                """.trimIndent(),
            )
        })
        try {
            val board = LichessBoardClient(client, testSession)
            assertEquals(
                listOf(LichessAccountEvent.Connected, LichessAccountEvent.ChallengeClosed("game0001")),
                board.accountEvents().toList(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun rateLimitSuppressesRequestsAcrossEndpointsForOneMinute() = runTest {
        var requests = 0
        var now = 0L
        val client = HttpClient(MockEngine {
            requests += 1
            if (requests == 1) respond("", HttpStatusCode.TooManyRequests)
            else respond("""{"nowPlaying":[]}""")
        })
        try {
            val board = LichessBoardClient(client, testSession, gate = LichessRequestGate { now })
            assertFailsWith<LichessException> { board.accountEvents().toList() }
            now = 59_999
            assertFailsWith<LichessException> { board.ongoingGames() }
            assertEquals(1, requests)
            now = 60_000
            assertEquals(emptyList(), board.ongoingGames())
            assertEquals(2, requests)
        } finally {
            client.close()
        }
    }
}

internal val testSession = LichessSession(LichessProfile("white", "White"), "test-token")
