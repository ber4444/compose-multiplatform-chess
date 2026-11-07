package com.example.myapplication.persistence.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.client.request.forms.FormDataContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirebaseAuthClientTest {
    @Test
    fun signsInAndRefreshesUsingTheSameAccount() = runTest {
        val requests = mutableListOf<String>()
        var now = 0L
        val engine = MockEngine { request ->
            requests += request.url.encodedPath
            when (request.url.encodedPath) {
                "/v1/accounts:signInWithPassword" ->
                    respond("""{"localId":"owner","idToken":"first","refreshToken":"refresh","expiresIn":"3600"}""")
                "/v1/accounts:lookup" -> respond("""{"users":[{"localId":"owner","emailVerified":true}]}""")
                "/v1/token" -> {
                    assertEquals("refresh", (request.body as FormDataContent).formData["refresh_token"])
                    respond("""{"user_id":"owner","id_token":"second","refresh_token":"new","expires_in":"3600"}""")
                }
                else -> error("Unexpected request")
            }
        }
        val client = FirebaseAuthClient(HttpClient(engine), "public-key", now = { now })
        val account = client.signIn("owner@example.org", "password")
        assertTrue(account.verified)
        assertEquals("first", client.refreshIfNeeded(account).idToken)
        now = 3_600_000
        assertEquals("second", client.refreshIfNeeded(account).idToken)
        assertEquals(listOf("/v1/accounts:signInWithPassword", "/v1/accounts:lookup", "/v1/token"), requests)
    }

    @Test
    fun keepsUnverifiedEmailOutOfSaveSessionAndSupportsVerificationAndReset() = runTest {
        val requests = mutableListOf<String>()
        val client = FirebaseAuthClient(HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/accounts:signUp", "/v1/accounts:signInWithPassword" ->
                    respond("""{"localId":"owner","idToken":"id","refreshToken":"refresh","expiresIn":"3600"}""")
                "/v1/accounts:lookup" -> respond("""{"users":[{"localId":"owner","emailVerified":false}]}""")
                "/v1/accounts:sendOobCode" -> {
                    requests += (request.body as TextContent).text
                    respond("{}", HttpStatusCode.OK)
                }
                else -> error("Unexpected request")
            }
        }), "public-key")
        assertFalse(client.signIn("owner@example.org", "password").verified)
        client.register("owner@example.org", "password")
        client.resetPassword("owner@example.org")
        assertTrue(requests.first().contains("VERIFY_EMAIL"))
        assertTrue(requests.last().contains("PASSWORD_RESET"))
    }
}
