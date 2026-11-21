package com.example.myapplication.persistence.cloud

import com.example.myapplication.persistence.nowEpochMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal interface CloudIdentity {
    suspend fun signIn(email: String, password: String): FirebaseSession
    suspend fun register(email: String, password: String)
    suspend fun resetPassword(email: String)
    suspend fun sendVerification(session: FirebaseSession)
    suspend fun refreshIfNeeded(session: FirebaseSession): FirebaseSession
}

// Keep credentials in the session only and out of generated toString() output.
internal class FirebaseSession(
    val uid: String,
    val idToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val verified: Boolean,
)

internal class FirebaseAuthClient(
    private val http: HttpClient,
    private val apiKey: String,
    private val now: () -> Long = ::nowEpochMillis,
) : CloudIdentity {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override suspend fun signIn(email: String, password: String): FirebaseSession {
        val credential = accountAction<Credentials, AuthReply>("signInWithPassword", Credentials(email, password))
        val user = accountAction<Token, UserLookup>("lookup", Token(credential.idToken))
            .users.singleOrNull { it.localId == credential.localId }
            ?: throw CloudSaveException(401)
        return credential.session(user.emailVerified)
    }

    override suspend fun register(email: String, password: String) {
        val credential = accountAction<Credentials, AuthReply>("signUp", Credentials(email, password))
        sendVerification(credential.session(verified = false))
    }

    override suspend fun sendVerification(session: FirebaseSession) {
        accountAction<MailRequest, MailReply>("sendOobCode", MailRequest("VERIFY_EMAIL", idToken = session.idToken))
    }

    override suspend fun resetPassword(email: String) {
        accountAction<MailRequest, MailReply>("sendOobCode", MailRequest("PASSWORD_RESET", email = email))
    }

    override suspend fun refreshIfNeeded(session: FirebaseSession): FirebaseSession {
        if (session.expiresAt - now() > 60_000) return session
        val response = http.post("https://securetoken.googleapis.com/v1/token") {
            parameter("key", apiKey)
            setBody(FormDataContent(Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", session.refreshToken)
            }))
        }
        if (!response.status.isSuccess()) throw CloudSaveException(response.status.value)
        val refreshed = json.decodeFromString<RefreshReply>(response.bodyAsText())
        if (refreshed.uid != session.uid) throw CloudSaveException(401)
        return FirebaseSession(
            refreshed.uid, refreshed.idToken, refreshed.refreshToken,
            expiration(refreshed.expiresIn), session.verified,
        )
    }

    private fun AuthReply.session(verified: Boolean) =
        FirebaseSession(localId, idToken, refreshToken, expiration(expiresIn), verified)

    private fun expiration(expiresIn: String): Long = now() + (expiresIn.toLongOrNull() ?: 0L) * 1_000

    private suspend inline fun <reified Request, reified Reply> accountAction(
        action: String,
        body: Request,
    ): Reply {
        val response = http.post("https://identitytoolkit.googleapis.com/v1/accounts:$action") {
            parameter("key", apiKey)
            setBody(TextContent(json.encodeToString(body), ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) throw CloudSaveException(response.status.value)
        return json.decodeFromString<Reply>(response.bodyAsText())
    }
}

@Serializable
private data class Credentials(val email: String, val password: String, val returnSecureToken: Boolean = true)

@Serializable
private data class Token(val idToken: String)

@Serializable
private data class MailRequest(val requestType: String, val email: String? = null, val idToken: String? = null)

@Serializable
private data class MailReply(val email: String? = null)

@Serializable
private data class AuthReply(
    val localId: String,
    val idToken: String,
    val refreshToken: String,
    val expiresIn: String,
)

@Serializable
private data class UserLookup(val users: List<AccountUser> = emptyList())

@Serializable
private data class AccountUser(val localId: String, val emailVerified: Boolean = false)

@Serializable
private data class RefreshReply(
    @SerialName("user_id") val uid: String,
    @SerialName("id_token") val idToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: String,
)
