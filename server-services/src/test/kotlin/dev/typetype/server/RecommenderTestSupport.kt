package dev.typetype.server

import dev.typetype.server.cache.CacheService
import dev.typetype.server.models.VideoItem
import dev.typetype.server.services.RecommenderConfig
import java.util.concurrent.ConcurrentHashMap

internal class RecommenderTestCache : CacheService {
    val values = ConcurrentHashMap<String, String>()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun set(key: String, value: String, ttlSeconds: Long) {
        values[key] = value
    }

    override suspend fun increment(key: String, ttlSeconds: Long): Long =
        values.merge(key, "1") { old, _ -> (old.toLong() + 1).toString() }.orEmpty().toLong()

    override suspend fun delete(key: String) {
        values.remove(key)
    }
}

internal fun recommenderTestConfig(
    serviceUrl: String? = "http://recommender.test",
    pageDeadlineMs: Long = 2_000,
    streamRequestsPerMinute: Int = 15,
    parallelBuilds: Int = 3,
): RecommenderConfig = RecommenderConfig(
    serviceUrl = serviceUrl,
    internalToken = null,
    requestTimeoutMs = 500,
    pageDeadlineMs = pageDeadlineMs,
    parallelBuilds = parallelBuilds,
    streamRequestsPerMinute = streamRequestsPerMinute,
    streamWallCooldownSeconds = 900,
    relatedTtlSeconds = 60,
    searchTtlSeconds = 60,
    channelTtlSeconds = 60,
    pageTtlSeconds = 60,
    settingsTtlSeconds = 60,
    historyKeyTtlSeconds = 60,
    historyMaxItems = 1_000,
    favoritesMaxItems = 1_000,
    historyChunkItems = 500,
    pageVariants = 3,
    maxSourceParallelism = 2,
)

internal fun recommenderTestVideo(id: String, channel: String = "channel"): VideoItem = VideoItem(
    id = id,
    title = "Video $id",
    url = "https://www.youtube.com/watch?v=$id",
    thumbnailUrl = "",
    uploaderName = channel,
    uploaderUrl = "https://www.youtube.com/channel/$channel",
    uploaderAvatarUrl = "",
    duration = 0,
    viewCount = 1_000,
    uploadDate = "",
    streamType = "video_stream",
    isShortFormContent = false,
    uploaderVerified = false,
    shortDescription = null,
)
