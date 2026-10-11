package dev.typetype.server.services

import dev.typetype.server.cache.CacheJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val INTERNAL_TOKEN_HEADER = "X-Internal-Token"
private const val HTTP_CONFLICT = 409

class RecommenderClient(
    private val config: RecommenderConfig,
    private val client: OkHttpClient = OkHttpClient(),
) {
    suspend fun health(): Boolean = get("/health", config.requestTimeoutMs) is RecommenderCallResult.Success

    suspend fun version(): JsonObject? =
        (get("/version", config.requestTimeoutMs) as? RecommenderCallResult.Success)?.body

    suspend fun algorithms(locale: String, timeoutMs: Long): RecommenderCallResult =
        get("/v1/algorithms?locale=${locale.take(MAX_LOCALE_CHARS)}", timeoutMs)

    suspend fun uploadHistory(body: JsonObject, timeoutMs: Long): RecommenderCallResult =
        post("/v1/history", body, timeoutMs)

    suspend fun plan(snapshot: JsonObject, timeoutMs: Long): RecommenderCallResult = post("/v1/plan", snapshot, timeoutMs)

    suspend fun rank(body: JsonObject, timeoutMs: Long): RecommenderCallResult = post("/v1/rank", body, timeoutMs)

    suspend fun profile(snapshot: JsonObject, timeoutMs: Long): RecommenderCallResult =
        post("/v1/profile", snapshot, timeoutMs)

    private suspend fun get(path: String, timeoutMs: Long): RecommenderCallResult =
        execute(timeoutMs) { it.url(config.serviceUrl + path).get() }

    private suspend fun post(path: String, body: JsonObject, timeoutMs: Long): RecommenderCallResult =
        execute(timeoutMs) {
            it.url(config.serviceUrl + path).post(
                CacheJson.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE),
            )
        }

    private suspend fun execute(
        timeoutMs: Long,
        configure: (Request.Builder) -> Request.Builder,
    ): RecommenderCallResult {
        if (!config.installed) return RecommenderCallResult.Failure("recommender not installed")
        return withContext(Dispatchers.IO) {
            runCatching {
                val builder = configure(Request.Builder())
                config.internalToken?.let { builder.header(INTERNAL_TOKEN_HEADER, it) }
                val request = builder.build()
                val call = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(request)
                call.execute().use { response ->
                    val text = response.body.string()
                    when {
                        response.code == HTTP_CONFLICT -> RecommenderCallResult.Expired
                        !response.isSuccessful -> RecommenderCallResult.Failure("HTTP ${response.code}")
                        text.isBlank() -> RecommenderCallResult.Success(JsonObject(emptyMap()))
                        else -> RecommenderCallResult.Success(CacheJson.parseToJsonElement(text).jsonObject)
                    }
                }
            }.getOrElse { RecommenderCallResult.Failure(it.javaClass.simpleName) }
        }
    }

    private companion object {
        const val MAX_LOCALE_CHARS = 12
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
