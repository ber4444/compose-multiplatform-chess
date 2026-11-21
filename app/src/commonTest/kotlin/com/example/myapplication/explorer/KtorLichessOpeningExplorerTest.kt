package com.example.myapplication.explorer

import com.example.myapplication.multiplayer.LichessException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorLichessOpeningExplorerTest {
    private val fen = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"

    // Shape from the published example (doc/specs/examples/openingExplorer-lichess.json.yaml).
    private val body = """
        {"white":600,"draws":100,"black":300,"opening":null,"topGames":[],"recentGames":[],
         "moves":[
           {"uci":"g1f3","san":"Nf3","averageRating":1510,"white":420,"draws":60,"black":220,"game":null,"opening":null},
           {"uci":"b1c3","san":"Nc3","averageRating":1490,"white":150,"draws":30,"black":70,"game":null,"opening":null}
         ]}
    """.trimIndent()

    @Test
    fun sendsTokenRatingGroupAndSpeedsAndParsesShares() = runTest {
        val engine = MockEngine { request ->
            val url = request.url
            assertEquals("explorer.lichess.org", url.host)
            assertEquals("/lichess", url.encodedPath)
            assertEquals(fen, url.parameters["fen"])
            assertEquals("1600", url.parameters["ratings"])
            assertEquals("blitz,rapid,classical", url.parameters["speeds"])
            assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
            assertEquals(LICHESS_USER_AGENT, request.headers[HttpHeaders.UserAgent])
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val stats = KtorLichessOpeningExplorer(HttpClient(engine)).lookup(fen, RatingBand.R1600, "tok")
        assertEquals(HumanMoveSource.LICHESS, stats.source)
        assertEquals(1000L, stats.totalGames)
        assertEquals(listOf("Nf3" to 0.7, "Nc3" to 0.25), stats.moves.map { it.san to it.share })
        assertEquals(700L, stats.moves.first().games)
    }

    @Test
    fun anonymousRejectionSurfacesTheStatus() = runTest {
        val engine = MockEngine { respond("401 Authorization Required", HttpStatusCode.Unauthorized) }
        val failure = assertFailsWith<LichessException> {
            KtorLichessOpeningExplorer(HttpClient(engine)).lookup(fen, RatingBand.R1600, "expired")
        }
        assertEquals(401, failure.status)
    }

    @Test
    fun rateLimitBacksOffWithoutAnotherRequest() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("", HttpStatusCode.TooManyRequests)
        }
        val explorer = KtorLichessOpeningExplorer(HttpClient(engine))
        repeat(2) {
            val failure = assertFailsWith<LichessException> { explorer.lookup(fen, RatingBand.R1600, "tok") }
            assertEquals(429, failure.status)
        }
        assertEquals(1, requests, "the second lookup must be refused locally for a minute")
    }
}
