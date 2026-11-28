package dev.typetype.server.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class GeminiProvider(
    private val config: GeminiConfig,
    private val timeoutMs: Long,
    proxy: Proxy? = null,
    baseClient: OkHttpClient = OkHttpClient(),
) : AiProvider {
    override val id: String = PROVIDER_ID
    override val model: String = config.primaryModel

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .apply {
            proxy?.let {
                proxy(it)
                connectionPool(ConnectionPool(PROXY_POOL_MAX_IDLE, PROXY_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
            }
        }
        .build()

    override suspend fun complete(request: AiCompletionRequest): AiCompletionResult {
        val apiKey = config.apiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: return AiCompletionResult.Disabled
        val models = config.models
        val totalBudgetMs = computeTotalBudgetMs(timeoutMs, models.size)
        return withContext(Dispatchers.IO) {
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalBudgetMs)
            var lastResult: AiCompletionResult = AiCompletionResult.Failed("upstream_unavailable", retryable = true)
            for ((index, targetModel) in models.withIndex()) {
                val attemptTimeoutMs = remainingAttemptTimeoutMs(deadlineNanos, isFirst = index == 0)
                if (attemptTimeoutMs == null) {
                    logger.warn(
                        "gemini_request event=fallback_skipped fromModel={} nextModel={} reason=timeout_budget_exhausted",
                        models[index - 1],
                        targetModel,
                    )
                    return@withContext lastResult
                }
                val outcome = executeForModel(request, apiKey, targetModel, attemptTimeoutMs)
                if (outcome is AiCompletionResult.Success) {
                    if (index > 0) {
                        logger.info("gemini_request event=fallback_succeeded model={}", targetModel)
                    }
                    return@withContext outcome
                }
                lastResult = outcome
                if (index == models.lastIndex || !GeminiResponseMapper.shouldFallback(outcome)) {
                    return@withContext outcome
                }
                logger.warn(
                    "gemini_request event=fallback fromModel={} toModel={} reason={}",
                    targetModel,
                    models[index + 1],
                    GeminiResponseMapper.describeFailure(outcome),
                )
            }
            lastResult
        }
    }

    private fun executeForModel(
        request: AiCompletionRequest,
        apiKey: String,
        targetModel: String,
        attemptTimeoutMs: Long,
    ): AiCompletionResult {
        val payload = GeminiCodec.buildRequestBody(request, config.thinkingLevelFor(targetModel))
        val httpRequest = Request.Builder()
            .url(buildEndpoint(config.baseUrl, targetModel))
            .header(API_KEY_HEADER, apiKey)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val attemptClient = clientForAttempt(attemptTimeoutMs)
        val startedAt = System.nanoTime()
        for (attempt in 0 until MAX_ATTEMPTS) {
            val call = attemptClient.newCall(httpRequest)
            try {
                return call.execute().use { response ->
                    val durationMs = (System.nanoTime() - startedAt) / MS_PER_NANOSECOND
                    GeminiResponseMapper.map(targetModel, response.code, response.body.string(), apiKey, durationMs)
                }
            } catch (e: IOException) {
                attemptClient.connectionPool.evictAll()
                val durationMs = (System.nanoTime() - startedAt) / MS_PER_NANOSECOND
                val redacted = GeminiCodec.redactApiKey(e.message.orEmpty(), apiKey)
                val willRetry = attempt == 0 &&
                    !call.isCanceled() &&
                    e !is InterruptedIOException &&
                    durationMs < attemptTimeoutMs / 2L
                logger.warn(
                    "gemini_request event=io_error model={} attempt={} retry={} durationMs={} errorType={} message={}",
                    targetModel,
                    attempt + 1,
                    willRetry,
                    durationMs,
                    e.javaClass.simpleName,
                    redacted,
                )
                if (!willRetry) {
                    return AiCompletionResult.Failed("upstream_unavailable", retryable = true)
                }
            }
        }
        return AiCompletionResult.Failed("upstream_unavailable", retryable = true)
    }

    private fun clientForAttempt(attemptTimeoutMs: Long): OkHttpClient {
        if (attemptTimeoutMs >= timeoutMs) return client
        return client.newBuilder()
            .connectTimeout(attemptTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(attemptTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(attemptTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(attemptTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun remainingAttemptTimeoutMs(deadlineNanos: Long, isFirst: Boolean): Long? {
        if (isFirst) return timeoutMs
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        val minFallbackMs = minOf(
            DEFAULT_MIN_FALLBACK_TIMEOUT_MS,
            (timeoutMs / 2L).coerceAtLeast(MIN_TEST_FALLBACK_TIMEOUT_MS),
        )
        if (remainingMs < minFallbackMs) return null
        return minOf(timeoutMs, remainingMs)
    }

    private fun computeTotalBudgetMs(perModelMs: Long, modelCount: Int): Long {
        if (modelCount <= 1) return perModelMs
        val scaled = perModelMs.coerceAtMost(Long.MAX_VALUE / modelCount) * modelCount
        return minOf(scaled, maxOf(perModelMs, MAX_CHAIN_TIMEOUT_MS))
    }

    private fun buildEndpoint(baseUrl: String, modelName: String): String {
        val encodedModel = URLEncoder.encode(modelName, StandardCharsets.UTF_8).replace("+", "%20")
        return "$baseUrl/v1beta/models/$encodedModel:generateContent"
    }

    companion object {
        const val PROVIDER_ID: String = "gemini"
        private const val API_KEY_HEADER = "x-goog-api-key"
        private const val MAX_ATTEMPTS = 2
        private const val MAX_CHAIN_TIMEOUT_MS = 60_000L
        private const val DEFAULT_MIN_FALLBACK_TIMEOUT_MS = 1_000L
        private const val MIN_TEST_FALLBACK_TIMEOUT_MS = 50L
        private const val PROXY_POOL_MAX_IDLE = 5
        private const val PROXY_KEEP_ALIVE_SECONDS = 25L
        private const val MS_PER_NANOSECOND = 1_000_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val logger = LoggerFactory.getLogger(GeminiProvider::class.java)
    }
}
