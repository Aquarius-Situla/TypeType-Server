package dev.typetype.server.services

import dev.typetype.server.cache.CacheJson
import dev.typetype.server.cache.CacheService
import dev.typetype.server.models.VideoItem
import kotlinx.serialization.builtins.ListSerializer

private val VIDEO_LIST = ListSerializer(VideoItem.serializer())

/**
 * What a source returned, kept for every user: with no account, YouTube answers the same to
 * everyone. An empty answer is not kept (it may be a refusal, not the truth).
 */
class RecommenderSourceCache(
    private val cache: CacheService,
    private val metrics: RecommenderMetrics,
) {
    suspend fun getOrLoad(key: String, ttlSeconds: Long, load: suspend () -> List<VideoItem>): List<VideoItem> {
        val cacheKey = "recommender:source:v1:$key"
        runCatching { cache.get(cacheKey) }.getOrNull()
            ?.let { cached -> runCatching { CacheJson.decodeFromString(VIDEO_LIST, cached) }.getOrNull() }
            ?.let {
                metrics.sourceCacheHits.incrementAndGet()
                return it
            }
        metrics.sourceRequests.incrementAndGet()
        val videos = load()
        if (videos.isNotEmpty() && ttlSeconds > 0L) {
            runCatching { cache.set(cacheKey, CacheJson.encodeToString(VIDEO_LIST, videos), ttlSeconds) }
        }
        return videos
    }
}
