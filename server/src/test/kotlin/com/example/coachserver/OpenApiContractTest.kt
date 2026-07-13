package com.example.coachserver

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.Passage
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class OpenApiContractTest {
    @Test
    fun `real health and opening responses satisfy openapi`() = testApplication {
        val passage = Passage("c20", "King's Pawn Game", "The center is contested by both king pawns.")
        val dependencies = ServerDependencies(
            embedder = Embedder { FloatArray(384) },
            passageRepository = object : PassageRepository {
                override fun retrieve(embedding: FloatArray, limit: Int) = listOf(passage)
                override fun upsert(passage: Passage, embedding: FloatArray) = Unit
            },
            composer = TemplateComposer(),
        )
        application { openingCoachModule(dependencies) }
        val validator = OpenApiInteractionValidator
            .createForSpecificationUrl(Path("openapi.yaml").toAbsolutePath().toUri().toString())
            .build()

        val healthResponse = client.get("/health")
        val healthReport = validator.validate(
            SimpleRequest.Builder.get("/health").build(),
            SimpleResponse.Builder.ok()
                .withContentType("text/plain; charset=UTF-8")
                .withBody(healthResponse.bodyAsText())
                .build(),
        )
        assertFalse(healthReport.hasErrors(), healthReport.messages.toString())

        val request = OpeningExplainRequest("fen", listOf("e4", "e5"), "C20", "en-US")
        val openingResponse = client.post("/v1/openings/explain") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(request))
        }
        val openingReport = validator.validate(
            SimpleRequest.Builder.post("/v1/openings/explain")
                .withContentType("application/json")
                .withBody(Json.encodeToString(request))
                .build(),
            SimpleResponse.Builder.ok()
                .withContentType("application/json")
                .withBody(openingResponse.bodyAsText())
                .build(),
        )
        assertFalse(openingReport.hasErrors(), openingReport.messages.toString())
    }
}
