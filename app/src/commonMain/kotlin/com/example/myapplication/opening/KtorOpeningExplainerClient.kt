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

/**
 * Whether this build has a cloud coach base URL at all (`CHESS_COACH_BASE_URL` env →
 * `coach.baseUrl` in `local.properties` → empty). Both cloud surfaces — Opening Explainer and
 * Position Chat — degrade to a fixed offline sentence without it, so this is what their `ProGate`
 * passes as `available`: an unconfigured build must not sell them.
 */
val cloudCoachConfigured: Boolean get() = OPENING_EXPLAINER_BASE_URL.isNotBlank()

fun createOpeningExplainer(): OpeningExplainer {
    val baseUrl = OPENING_EXPLAINER_BASE_URL.trim()
    val client = baseUrl.takeIf(String::isNotEmpty)?.let {
        KtorOpeningExplainerClient(
            httpClient = HttpClient(openingExplainerHttpClientEngine()) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            },
            baseUrl = it,
        )
    }
    return DefaultOpeningExplainer(
        client = client,
        contextProvider = {
            AiContextSnapshot(
                // Belt-and-braces, not the guarantee — see the identical note in
                // `KtorStreamingChatClient`. `AiRoutePolicies.openingExplainer` sets
                // `allowLocal = false`, which is what actually keeps this surface on `:server`.
                availableLocalVendors = emptyList(),
                isNetworkAvailable = client != null,
                isAppForegrounded = true,
                userSetting = AiUserSetting.ALLOW_CLOUD,
            )
        },
    )
}
