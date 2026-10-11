package dev.typetype.server.services

import dev.typetype.server.cache.CacheService

class RecommenderHistoryService(
    private val client: RecommenderClient,
    private val cache: CacheService,
    private val config: RecommenderConfig,
) {
    suspend fun ensureSent(userId: String, timeoutMs: Long, filter: RecommenderDataFilter, force: Boolean = false): String? {
        val includeHistory = filter.uses(RecommenderDataFilter.HISTORY) ||
            filter.uses(RecommenderDataFilter.SHORTS_HISTORY)
        val includeFavorites = filter.uses(RecommenderDataFilter.FAVORITES)
        if (!includeHistory && !includeFavorites) return null
        val fingerprint = RecommenderHistoryFingerprint.of(userId, includeHistory, includeFavorites)
        val key = listOf(fingerprint, filter.digest()).filter { it.isNotEmpty() }.joinToString("-")
        if (!force && runCatching { cache.get(sentKey(key)) }.getOrNull() != null) return key
        val watches = if (includeHistory) {
            filter.history(RecommenderUserData.watches(userId, config.historyMaxItems))
        } else {
            emptyList()
        }
        val positions: Map<String, Long> = if (watches.isEmpty()) {
            emptyMap()
        } else {
            RecommenderUserData.positionsMs(userId, watches.filter { it.duration > 0L }.map { it.url })
        }
        val likes = if (includeFavorites) {
            filter.favorites(RecommenderUserData.likes(userId, config.favoritesMaxItems))
        } else {
            emptyList()
        }
        val size = config.historyChunkItems
        val parts = maxOf(1, (watches.size + size - 1) / size, (likes.size + size - 1) / size)
        for (part in 0 until parts) {
            val body = RecommenderSnapshotBuilder.historyUpload(
                key = key,
                history = watches.drop(part * size).take(size),
                positionsMs = positions,
                favorites = likes.drop(part * size).take(size),
                part = part,
                last = part == parts - 1,
            )
            val sent = client.uploadHistory(body, timeoutMs * UPLOAD_TIMEOUT_FACTOR)
            if (sent !is RecommenderCallResult.Success) return null
        }
        runCatching { cache.set(sentKey(key), "1", config.historyKeyTtlSeconds) }
        return key
    }

    private fun sentKey(key: String): String = "recommender:history-sent:v1:$key"

    private companion object {
        const val UPLOAD_TIMEOUT_FACTOR = 4L
    }
}
