package dev.typetype.server.services

import dev.typetype.server.cache.CacheJson
import dev.typetype.server.cache.CacheService
import dev.typetype.server.models.RecommenderPageTrace
import dev.typetype.server.models.RecommenderSettingsItem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

private const val MILLIS_PER_SECOND = 1_000L

class RecommenderPageStore(
    private val cache: CacheService,
    private val config: RecommenderConfig,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun nextVariant(userId: String): Int {
        val turn = runCatching { cache.increment("recommender:turn:v1:$userId", config.pageTtlSeconds) }.getOrNull() ?: 0L
        return (turn % config.pageVariants).toInt()
    }

    fun seedFor(variant: Int): Int {
        val window = now() / (config.pageTtlSeconds.coerceAtLeast(1L) * MILLIS_PER_SECOND)
        return (window * config.pageVariants + variant).toInt()
    }

    suspend fun get(userId: String, algorithmId: String, settings: RecommenderSettingsItem, variant: Int): RecommenderPage? =
        runCatching { cache.get(key(userId, algorithmId, settings, variant)) }.getOrNull()
            ?.let { runCatching { CacheJson.decodeFromString(RecommenderPage.serializer(), it) }.getOrNull() }

    suspend fun put(userId: String, settings: RecommenderSettingsItem, variant: Int, page: RecommenderPage) {
        runCatching {
            cache.set(
                key(userId, page.algorithm, settings, variant),
                CacheJson.encodeToString(RecommenderPage.serializer(), page),
                config.pageTtlSeconds,
            )
            page.trace?.let {
                cache.set(lastKey(userId), CacheJson.encodeToString(RecommenderPageTrace.serializer(), it), LAST_TRACE_TTL_SECONDS)
            }
        }
    }

    suspend fun lastTrace(userId: String): RecommenderPageTrace? =
        runCatching { cache.get(lastKey(userId)) }.getOrNull()
            ?.let { runCatching { CacheJson.decodeFromString(RecommenderPageTrace.serializer(), it) }.getOrNull() }

    suspend fun hidden(userId: String): Set<String> =
        runCatching { cache.get(hiddenKey(userId)) }.getOrNull()
            ?.let { runCatching { CacheJson.decodeFromString(HIDDEN, it) }.getOrNull() }
            ?.toSet().orEmpty()

    suspend fun hide(userId: String, videoUrl: String, hide: Boolean = true) {
        val urls = hidden(userId).let { if (hide) it + videoUrl else it - videoUrl }.toList().takeLast(MAX_HIDDEN)
        runCatching { cache.set(hiddenKey(userId), CacheJson.encodeToString(HIDDEN, urls), HIDDEN_TTL_SECONDS) }
    }

    suspend fun forget(userId: String) {
        runCatching { cache.increment(generationKey(userId), GENERATION_TTL_SECONDS) }
    }

    private suspend fun key(userId: String, algorithmId: String, settings: RecommenderSettingsItem, variant: Int): String {
        val generation = runCatching { cache.get(generationKey(userId)) }.getOrNull() ?: "0"
        val settingsHash = settings.copy(enabled = true).hashCode()
        return "recommender:page:v1:$userId:$generation:$algorithmId:$settingsHash:${seedFor(variant)}"
    }

    private fun generationKey(userId: String): String = "recommender:generation:v1:$userId"

    private fun lastKey(userId: String): String = "recommender:last-trace:v1:$userId"

    private fun hiddenKey(userId: String): String = "recommender:hidden:v1:$userId"

    private companion object {
        const val GENERATION_TTL_SECONDS = 30L * 24 * 3_600
        const val LAST_TRACE_TTL_SECONDS = 24L * 3_600
        const val HIDDEN_TTL_SECONDS = 24L * 3_600
        const val MAX_HIDDEN = 500
        val HIDDEN = ListSerializer(String.serializer())
    }
}
