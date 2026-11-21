package com.example.myapplication.multiplayer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LichessAuthTest {
    @Test
    fun checksStateBeforeExchangingTheCode() = runTest {
        val client = HttpClient(MockEngine {
            error("A stale callback must not exchange a code")
        })
        val platform = object : OnlineOAuthPlatform {
            override fun randomBytes(size: Int) = ByteArray(size) { it.toByte() }
            override suspend fun sha256(bytes: ByteArray) = ByteArray(32) { 1 }
            override suspend fun authorize(buildUrl: (String) -> String): OnlineOAuthCallback {
                val redirect = "https://chess.example.test/callback"
                val url = Url(buildUrl(redirect))
                assertEquals("S256", url.parameters["code_challenge_method"])
                assertEquals("board:play", url.parameters["scope"])
                assertEquals("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE", url.parameters["code_challenge"])
                assertTrue("code_verifier" !in url.parameters.names())
                return OnlineOAuthCallback(redirect, "stale-state", "code-from-a-previous-request")
            }
        }
        try {
            assertFailsWith<LichessException> { LichessAuth(client, platform).authorize() }
        } finally {
            client.close()
        }
    }

    @Test
    fun exchangesOnlyOnceAndRedactsTheSession() = runTest {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/token" -> respond("""{"access_token":"private-token","token_type":"Bearer"}""")
                "/api/account" -> respond("""{"id":"player","username":"Player"}""")
                else -> error("Unexpected request")
            }
        })
        val platform = object : OnlineOAuthPlatform {
            override fun randomBytes(size: Int) = ByteArray(size) { it.toByte() }
            override suspend fun sha256(bytes: ByteArray) = ByteArray(32)
            override suspend fun authorize(buildUrl: (String) -> String): OnlineOAuthCallback {
                val redirect = "https://chess.example.test/callback"
                val state = Url(buildUrl(redirect)).parameters["state"]
                return OnlineOAuthCallback(redirect, state, "code")
            }
        }
        try {
            val session = LichessAuth(client, platform).authorize()
            assertEquals(LichessProfile("player", "Player"), session.profile)
            assertEquals(listOf("/api/token", "/api/account"), requests)
            assertTrue("private-token" !in session.toString())
        } finally {
            client.close()
        }
    }
}
