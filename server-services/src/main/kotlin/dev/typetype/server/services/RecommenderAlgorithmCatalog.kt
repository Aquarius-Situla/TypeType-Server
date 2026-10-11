package dev.typetype.server.services

import dev.typetype.server.cache.CacheJson
import dev.typetype.server.cache.CacheService
import dev.typetype.server.models.RecommenderAlgorithmName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RecommenderAlgorithmCatalog(
    private val client: RecommenderClient,
    private val cache: CacheService,
    private val config: RecommenderConfig,
    private val adminSettingsService: AdminSettingsService,
) {
    suspend fun list(locale: String): JsonObject? {
        val all = load(locale) ?: return null
        val allowed = adminSettingsService.get().recommenderAllowedAlgorithms
        val offered = all.algorithms().filter { allowed.isEmpty() || it.id() in allowed }
        return JsonObject(all + ("algorithms" to JsonArray(offered)) + ("default_algorithm" to defaultOf(all, offered)))
    }

    suspend fun allNames(locale: String): List<RecommenderAlgorithmName> =
        load(locale)?.algorithms().orEmpty().mapNotNull { algorithm ->
            algorithm.id()?.let { id ->
                RecommenderAlgorithmName(id, algorithm["name"]?.jsonPrimitive?.contentOrNull ?: id)
            }
        }

    suspend fun offeredIds(locale: String): List<String> = list(locale)?.algorithms()?.mapNotNull { it.id() }.orEmpty()

    suspend fun defaultId(locale: String): String? =
        list(locale)?.get("default_algorithm")?.jsonPrimitive?.contentOrNull

    private suspend fun defaultOf(all: JsonObject, offered: List<JsonObject>): JsonElement {
        val ids = offered.mapNotNull { it.id() }
        val chosen = adminSettingsService.get().recommenderDefaultAlgorithm?.takeIf { it in ids }
            ?: all["default_algorithm"]?.jsonPrimitive?.contentOrNull?.takeIf { it in ids }
            ?: ids.firstOrNull()
        return JsonPrimitive(chosen)
    }

    private suspend fun load(locale: String): JsonObject? {
        val key = "recommender:algorithms:v1:$locale"
        runCatching { cache.get(key) }.getOrNull()
            ?.let { runCatching { CacheJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.let { return it }
        val result = client.algorithms(locale, config.requestTimeoutMs) as? RecommenderCallResult.Success ?: return null
        runCatching {
            cache.set(key, CacheJson.encodeToString(JsonObject.serializer(), result.body), config.settingsTtlSeconds)
        }
        return result.body
    }

    private fun JsonObject.algorithms(): List<JsonObject> = this["algorithms"]?.jsonArray?.map { it.jsonObject }.orEmpty()

    private fun JsonObject.id(): String? = this["id"]?.jsonPrimitive?.contentOrNull
}
