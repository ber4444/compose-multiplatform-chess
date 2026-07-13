package com.example.myapplication.opening

import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.OpeningExplainResponse
import com.example.ondeviceai.AiContextSnapshot
import com.example.ondeviceai.AiUserSetting
import com.example.ondeviceai.DefaultOpeningExplainer
import com.example.ondeviceai.OpeningExplainer
import com.example.ondeviceai.OpeningExplainerClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class OpeningExplainerHttpException(
    val statusCode: Int,
) : Exception("Opening explainer returned HTTP $statusCode")

class KtorOpeningExplainerClient(
    private val httpClient: HttpClient,
    baseUrl: String,
) : OpeningExplainerClient {
    private val endpoint = "${baseUrl.trimEnd('/')}/v1/openings/explain"

    init {
        require(baseUrl.isNotBlank()) { "opening explainer base URL must not be blank" }
    }

    override suspend fun explain(request: OpeningExplainRequest): OpeningExplainResponse {
        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value !in 200..299) {
            throw OpeningExplainerHttpException(response.status.value)
        }
        return response.body()
    }

    override fun close() {
        httpClient.close()
    }
}

internal expect fun openingExplainerHttpClientEngine(): HttpClientEngine

fun createOpeningExplainer(): OpeningExplainer {
    val baseUrl = OPENING_EXPLAINER_BASE_URL.trim()
    val client = baseUrl.takeIf(String::isNotEmpty)?.let {
        KtorOpeningExplainerClient(
            httpClient = HttpClient(openingExplainerHttpClientEngine()) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = false })
                }
            },
            baseUrl = it,
        )
    }
    return DefaultOpeningExplainer(
        client = client,
        contextProvider = {
            AiContextSnapshot(
                isDeviceModelAvailable = false,
                isNetworkAvailable = client != null,
                isAppForegrounded = true,
                userSetting = AiUserSetting.ALLOW_CLOUD,
            )
        },
    )
}
