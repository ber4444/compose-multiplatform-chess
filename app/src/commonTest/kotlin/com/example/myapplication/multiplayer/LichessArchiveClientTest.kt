package com.example.myapplication.multiplayer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LichessArchiveClientTest {
    @Test
    fun exportsFinishedGamesAndPgnDirectlyFromLichess() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("lichess.org", request.url.host)
            when (request.url.encodedPath) {
                "/api/games/user/White" -> {
                    assertEquals("50", request.url.parameters["max"])
                    assertEquals("false", request.url.parameters["ongoing"])
                    respond("""
                        {"id":"game0001","createdAt":1,"status":"started","players":{}}
                        {"id":"game0002","createdAt":2,"status":"aborted","players":{}}
                        {"id":"game0003","createdAt":3,"status":"outoftime","winner":"white","moves":"e4 e5","players":{"white":{"user":{"name":"White","id":"white"}},"black":{"user":{"name":"Black","id":"black"}}}}
                    """.trimIndent())
                }
                "/game/export/game0003" -> respond("""[Result "1-0"]""")
                else -> error("Unexpected route")
            }
        })
        try {
            val archive = LichessArchiveClient(client)
            val entry = archive.recentFinished("White").toList().single()
            assertEquals("game0003", entry.id)
            assertEquals("White", entry.white.displayName)
            assertEquals("white", entry.winner)
            assertEquals(2, entry.moveCount)
            assertEquals("""[Result "1-0"]""", archive.exportPgn(entry.id))
        } finally {
            client.close()
        }
    }
}
