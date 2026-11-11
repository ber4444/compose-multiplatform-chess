package com.example.myapplication.persistence.cloud

import com.example.saveapi.CloudAccount
import com.example.saveapi.CloudHistoryPage
import com.example.saveapi.CloudResumeGame
import com.example.saveapi.CloudResumeSlot
import com.example.saveapi.CloudSaveError
import com.example.saveapi.CloudSavedGame
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

internal interface CloudSaveTransport {
    suspend fun account(token: String): CloudAccount
    suspend fun deleteAccount(token: String)
    suspend fun history(token: String, cursor: String?): CloudHistoryPage
    suspend fun game(token: String, id: String): CloudSavedGame
    suspend fun writeHistory(token: String, write: HistoryWrite)
    suspend fun current(token: String): RemoteResume
    suspend fun writeResume(token: String, write: ResumeWrite): Long
}

data class RemoteResume(val game: CloudResumeGame?, val revision: Long)

internal class CloudSaveException(val status: Int, val code: String? = null) : Exception("Cloud request failed ($status)")

internal class CloudSaveClient(private val http: HttpClient, baseUrl: String) : CloudSaveTransport {
    private val base = baseUrl.trimEnd('/') + "/v1/saves"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun account(token: String): CloudAccount =
        json.decodeFromString(call(token, HttpMethod.Get, "account").bodyAsText())

    override suspend fun deleteAccount(token: String) {
        call(token, HttpMethod.Delete, "account")
    }

    override suspend fun history(token: String, cursor: String?): CloudHistoryPage =
        json.decodeFromString(call(token, HttpMethod.Get, "history") {
            cursor?.let { parameter("cursor", it) }
        }.bodyAsText())

    override suspend fun game(token: String, id: String): CloudSavedGame =
        json.decodeFromString(call(token, HttpMethod.Get, "history/${safeId(id)}").bodyAsText())

    override suspend fun writeHistory(token: String, write: HistoryWrite) {
        val path = "history/${safeId(write.id)}"
        val game = write.game
        if (game == null) {
            call(token, HttpMethod.Delete, path)
        } else {
            call(token, HttpMethod.Put, path) { setBody(jsonContent(game)) }
        }
    }

    override suspend fun current(token: String): RemoteResume {
        val response = call(token, HttpMethod.Get, "current")
        val slot = json.decodeFromString<CloudResumeSlot>(response.bodyAsText())
        return RemoteResume(slot.game, response.revision())
    }

    override suspend fun writeResume(token: String, write: ResumeWrite): Long {
        val method = if (write.game == null) HttpMethod.Delete else HttpMethod.Put
        return call(token, method, "current") {
            header(HttpHeaders.IfMatch, "\"${checkNotNull(write.expectedRevision)}\"")
            header("Idempotency-Key", write.operationId)
            write.game?.let { setBody(jsonContent(it)) }
        }.revision()
    }

    private suspend fun call(
        token: String,
        method: HttpMethod,
        path: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val response = http.request("$base/$path") {
            this.method = method
            bearerAuth(token)
            block()
        }
        if (!response.status.isSuccess()) {
            val error = runCatching { json.decodeFromString<CloudSaveError>(response.bodyAsText()) }.getOrNull()
            throw CloudSaveException(response.status.value, error?.error)
        }
        return response
    }

    private inline fun <reified T> jsonContent(value: T) = TextContent(json.encodeToString(value), ContentType.Application.Json)

    private fun HttpResponse.revision(): Long = headers[HttpHeaders.ETag]?.removeSurrounding("\"")?.toLongOrNull()
        ?.takeIf { it >= 0 } ?: throw CloudSaveException(502)

    private fun safeId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid game ID" }
        return id
    }
}
