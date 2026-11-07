package com.example.myapplication.multiplayer

import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal data class OnlineOAuthCallback(
    val redirectUri: String,
    val state: String?,
    val code: String?,
    val error: String? = null,
)

@Serializable
internal data class LichessProfile(val id: String, val username: String)

internal class LichessSession(val profile: LichessProfile, internal val token: String)

internal interface LichessAuthorization {
    suspend fun authorize(): LichessSession
    suspend fun revoke(session: LichessSession)
}

internal class LichessAuth(
    private val client: HttpClient,
    private val platform: OnlineOAuthPlatform,
    private val baseUrl: String = LICHESS_BASE_URL,
    private val gate: LichessRequestGate = LichessRequestGate(),
) : LichessAuthorization {
    private val mutex = Mutex()

    override suspend fun authorize(): LichessSession = mutex.withLock {
        val verifier = base64Url(platform.randomBytes(32))
        val state = base64Url(platform.randomBytes(32))
        val challenge = base64Url(platform.sha256(verifier.encodeToByteArray()))
        var expectedRedirect: String? = null
        val callback = platform.authorize { redirect ->
            check(expectedRedirect == null) { "Authorization request already opened" }
            expectedRedirect = redirect
            URLBuilder(baseUrl.trimEnd('/') + "/oauth").apply {
                parameters.append("response_type", "code")
                parameters.append("client_id", CLIENT_ID)
                parameters.append("redirect_uri", redirect)
                parameters.append("scope", "board:play")
                parameters.append("state", state)
                parameters.append("code_challenge_method", "S256")
                parameters.append("code_challenge", challenge)
            }.buildString()
        }
        if (callback.redirectUri != expectedRedirect || callback.state != state) {
            throw LichessException("This sign-in response did not match the pending request.")
        }
        val code = callback.code
        if (callback.error != null || code.isNullOrBlank()) throw LichessException("Lichess sign-in was canceled.")
        val authHttp = LichessHttp(client, baseUrl, gate)
        val response = authHttp.text("/api/token", HttpMethod.Post, form = Parameters.build {
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", callback.redirectUri)
            append("client_id", CLIENT_ID)
            append("code_verifier", verifier)
        })
        val token = lichessJson.decodeFromString<Token>(response).accessToken
        val authenticated = LichessHttp(client, baseUrl, gate, token)
        val profile = lichessJson.decodeFromString<LichessProfile>(authenticated.text("/api/account"))
        LichessSession(profile, token)
    }

    override suspend fun revoke(session: LichessSession) {
        LichessHttp(client, baseUrl, gate, session.token).text("/api/token", HttpMethod.Delete)
    }

    @Serializable
    private class Token(@SerialName("access_token") val accessToken: String)

    private companion object {
        const val CLIENT_ID = "io.github.ber4444.chess"

        @OptIn(ExperimentalEncodingApi::class)
        fun base64Url(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')
    }
}
