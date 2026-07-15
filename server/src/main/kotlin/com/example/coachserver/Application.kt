package com.example.coachserver

import com.example.coachapi.ApiError
import com.example.coachapi.ChatStreamEvent
import com.example.coachapi.OpeningExplainRequest
import com.example.coachapi.PositionChatRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeStringUtf8
import kotlinx.io.readByteArray
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    val environment = System.getenv()
    val dependencies = defaultDependencies(environment)
    val chatService = runCatching { defaultChatDependencies(environment) }.getOrNull()
    embeddedServer(
        factory = Netty,
        host = "0.0.0.0",
        port = environment["PORT"]?.toIntOrNull() ?: 8080,
    ) {
        val allowedOrigins = environment["COACH_ALLOWED_ORIGINS"]
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()
        openingCoachModule(
            dependencies = dependencies,
            chatService = chatService,
            allowedOrigins = allowedOrigins,
            trustFlyClientIp = environment["FLY_APP_NAME"] != null,
        )
    }.start(wait = true)
}

fun Application.openingCoachModule(
    dependencies: ServerDependencies,
    chatService: PositionChatService? = null,
    allowedOrigins: Set<String> = emptySet(),
    rateLimiter: RequestRateLimiter = FixedWindowRateLimiter(),
    trustFlyClientIp: Boolean = false,
) {
    val logger = environment.log
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = false })
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", cause.message ?: "invalid request"))
        }
        exception<SerializationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", cause.message ?: "invalid JSON"))
        }
        exception<Throwable> { call, cause ->
            logger.error("Opening explainer failed", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "opening explanation failed"))
        }
    }
    if (allowedOrigins.isNotEmpty()) {
        install(CORS) {
            allowMethod(io.ktor.http.HttpMethod.Post)
            allowHeader(io.ktor.http.HttpHeaders.ContentType)
            allowedOrigins.forEach { host -> allowHost(host, schemes = listOf("http", "https")) }
        }
    }

    val service = OpeningService(dependencies)
    routing {
        get("/health") {
            call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
        }
        post("/v1/openings/explain") {
            if ((call.request.contentLength() ?: 0L) > MAX_REQUEST_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("request_too_large", "request body is too large"))
                return@post
            }
            val clientKey = if (trustFlyClientIp) {
                call.request.headers[FLY_CLIENT_IP_HEADER]
                    ?.takeIf { it.length <= 45 && it.all { char -> char.isDigit() || char in ".:abcdefABCDEF" } }
                    ?: call.request.origin.remoteHost
            } else {
                call.request.origin.remoteHost
            }
            if (!rateLimiter.tryAcquire(clientKey)) {
                call.respond(HttpStatusCode.TooManyRequests, ApiError("rate_limited", "too many requests"))
                return@post
            }
            val bytes = call.receiveChannel().readRemaining(MAX_REQUEST_BYTES + 1).readByteArray()
            if (bytes.size > MAX_REQUEST_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("request_too_large", "request body is too large"))
                return@post
            }
            call.respond(service.explain(REQUEST_JSON.decodeFromString<OpeningExplainRequest>(bytes.decodeToString())))
        }
        // Interactive, multi-turn, token-streaming chat about a single position. Uses genuine SSE
        // wire format (`data: <json>\n\n`) over a chunked `respondBytesWriter` response rather than
        // the ktor SSE plugin: the plugin's `sse { }` route builder is GET-only, but chat needs to
        // POST a request body (FEN + bounded history). Cancelling the collecting Job closes the
        // writer and aborts the underlying provider stream (see KtorStreamingChatClient on the app
        // side). Validation runs on the accumulated text at stream end; a failure emits a final
        // `fallback` event carrying deterministic text instead of `done`.
        if (chatService != null) {
            post("/v1/positions/chat/stream") {
                if ((call.request.contentLength() ?: 0L) > MAX_REQUEST_BYTES) {
                    call.respond(HttpStatusCode.PayloadTooLarge, ApiError("request_too_large", "request body is too large"))
                    return@post
                }
                val clientKey = if (trustFlyClientIp) {
                    call.request.headers[FLY_CLIENT_IP_HEADER]
                        ?.takeIf { it.length <= 45 && it.all { char -> char.isDigit() || char in ".:abcdefABCDEF" } }
                        ?: call.request.origin.remoteHost
                } else {
                    call.request.origin.remoteHost
                }
                if (!rateLimiter.tryAcquire(clientKey)) {
                    call.respond(HttpStatusCode.TooManyRequests, ApiError("rate_limited", "too many requests"))
                    return@post
                }
                val bytes = call.receiveChannel().readRemaining(MAX_REQUEST_BYTES + 1).readByteArray()
                if (bytes.size > MAX_REQUEST_BYTES) {
                    call.respond(HttpStatusCode.PayloadTooLarge, ApiError("request_too_large", "request body is too large"))
                    return@post
                }
                val request = REQUEST_JSON.decodeFromString<PositionChatRequest>(bytes.decodeToString())
                val stream = chatService.chat(request)
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    stream.collect { chunk ->
                        writeStringUtf8("data: ${REQUEST_JSON.encodeToString(chunk.toEvent())}\n\n")
                    }
                }
            }
        }
    }
}

fun defaultDependencies(environment: Map<String, String>): ServerDependencies {
    val dataSource = createDataSource(requireEnvironment(environment, "DATABASE_URL"))
    applySchema(dataSource)
    val embedder = OnnxMiniLmEmbedder(
        modelPath = Path.of(requireEnvironment(environment, "COACH_EMBEDDING_MODEL")),
        vocabPath = Path.of(requireEnvironment(environment, "COACH_EMBEDDING_VOCAB")),
    )
    val template = TemplateComposer()
    val composer = environment["COACH_LLM_API_KEY"]?.takeIf(String::isNotBlank)?.let { apiKey ->
        val inputPrice = environment["COACH_LLM_INPUT_USD_PER_MILLION"]?.toDoubleOrNull()
        val outputPrice = environment["COACH_LLM_OUTPUT_USD_PER_MILLION"]?.toDoubleOrNull()
        if (inputPrice == null || outputPrice == null || inputPrice < 0.0 || outputPrice < 0.0) {
            return@let template
        }
        LlmComposer(
            client = OpenAiCompatibleLlmClient(
                apiKey = apiKey,
                endpoint = URI(environment["COACH_LLM_API_URL"] ?: "https://api.openai.com/v1/chat/completions"),
                model = environment["COACH_LLM_MODEL"] ?: "gpt-4.1-mini",
                httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(PROVIDER_TIMEOUT_MS))
                    .build(),
                requestTimeout = Duration.ofMillis(PROVIDER_TIMEOUT_MS),
            ),
            fallback = template,
            budget = ProviderCostBudget(
                maxUsdCents = 0.2,
                inputUsdPerMillionTokens = inputPrice,
                outputUsdPerMillionTokens = outputPrice,
            ),
        )
    } ?: template
    return ServerDependencies(embedder, PostgresPassageRepository(dataSource), composer)
}

/**
 * Builds the position-chat service. Shares the embedder + passage repository with the opening
 * explainer (same retrieval index) and constructs a streaming LLM composer under the same
 * `COACH_LLM_*` config precedence; without a key + prices it falls back to [TemplateChatComposer].
 * Returns `null` only if the shared embedding deps can't be built (caller already failed).
 */
fun defaultChatDependencies(environment: Map<String, String>): PositionChatService {
    val dataSource = createDataSource(requireEnvironment(environment, "DATABASE_URL"))
    applySchema(dataSource)
    val embedder = OnnxMiniLmEmbedder(
        modelPath = Path.of(requireEnvironment(environment, "COACH_EMBEDDING_MODEL")),
        vocabPath = Path.of(requireEnvironment(environment, "COACH_EMBEDDING_VOCAB")),
    )
    val template = TemplateChatComposer()
    val composer: StreamingChatComposer = environment["COACH_LLM_API_KEY"]?.takeIf(String::isNotBlank)?.let { apiKey ->
        val inputPrice = environment["COACH_LLM_INPUT_USD_PER_MILLION"]?.toDoubleOrNull()
        val outputPrice = environment["COACH_LLM_OUTPUT_USD_PER_MILLION"]?.toDoubleOrNull()
        if (inputPrice == null || outputPrice == null || inputPrice < 0.0 || outputPrice < 0.0) {
            return@let template
        }
        LlmChatComposer(
            client = OpenAiCompatibleStreamingLlmClient(
                apiKey = apiKey,
                endpoint = URI(environment["COACH_LLM_API_URL"] ?: "https://api.openai.com/v1/chat/completions"),
                model = environment["COACH_LLM_MODEL"] ?: "gpt-4.1-mini",
                httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(PROVIDER_TIMEOUT_MS))
                    .build(),
                requestTimeout = Duration.ofMillis(CHAT_PROVIDER_TIMEOUT_MS),
            ),
            fallback = template,
            budget = ProviderCostBudget(
                maxUsdCents = 0.2,
                inputUsdPerMillionTokens = inputPrice,
                outputUsdPerMillionTokens = outputPrice,
            ),
        )
    } ?: template
    return PositionChatService(
        ChatServerDependencies(embedder, PostgresPassageRepository(dataSource), composer),
    )
}

private fun requireEnvironment(environment: Map<String, String>, name: String): String =
    requireNotNull(environment[name]?.takeIf(String::isNotBlank)) { "$name must be set" }

fun interface RequestRateLimiter {
    fun tryAcquire(key: String): Boolean
}

class FixedWindowRateLimiter(
    private val maxRequests: Int = 30,
    private val windowMillis: Long = 60_000,
    private val maxEntries: Int = 10_000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : RequestRateLimiter {
    private data class Window(val startedAt: Long, val count: Int)
    private val windows = ConcurrentHashMap<String, Window>()
    private val acquisitions = AtomicInteger()

    init {
        require(maxRequests > 0 && windowMillis > 0 && maxEntries > 0)
    }

    override fun tryAcquire(key: String): Boolean {
        var accepted = false
        val now = nowMillis()
        if (acquisitions.incrementAndGet() % CLEANUP_INTERVAL == 0) {
            windows.entries.removeIf { now - it.value.startedAt >= windowMillis }
        }
        if (!windows.containsKey(key) && windows.size >= maxEntries) {
            windows.entries.minByOrNull { it.value.startedAt }?.let { windows.remove(it.key, it.value) }
        }
        windows.compute(key) { _, current ->
            when {
                current == null || now - current.startedAt >= windowMillis -> {
                    accepted = true
                    Window(now, 1)
                }
                current.count < maxRequests -> {
                    accepted = true
                    current.copy(count = current.count + 1)
                }
                else -> current
            }
        }
        return accepted
    }
}

private const val MAX_REQUEST_BYTES = 16 * 1024L
private const val PROVIDER_TIMEOUT_MS = 5_000L
// Chat streams token-by-token, so it gets a longer per-request ceiling than the one-shot explainer.
private const val CHAT_PROVIDER_TIMEOUT_MS = 30_000L
private const val FLY_CLIENT_IP_HEADER = "Fly-Client-IP"
private const val CLEANUP_INTERVAL = 256
private val REQUEST_JSON = Json { ignoreUnknownKeys = false }
