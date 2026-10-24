package dev.typetype.server.services

import dev.typetype.server.cache.CacheJson
import dev.typetype.server.cache.CacheService
import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.models.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.ConcurrentHashMap

private val VIDEO_LIST = ListSerializer(VideoItem.serializer())

class CachedRelatedVideosService(
    private val delegate: RelatedVideosService,
    private val cache: CacheService,
) : RelatedVideosService {
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ExtractionResult<List<VideoItem>>>>()

    override suspend fun related(videoUrl: String): ExtractionResult<List<VideoItem>> {
        val videoId = youtubeVideoId(videoUrl) ?: return delegate.related(videoUrl)
        val key = "$CACHE_PREFIX:$videoId"
        cached(key)?.let { return ExtractionResult.Success(it) }

        val pending = CompletableDeferred<ExtractionResult<List<VideoItem>>>()
        val existing = inFlight.putIfAbsent(key, pending)
        if (existing != null) return existing.await()

        return try {
            val result = getCachedOrLoad(videoUrl, key)
            pending.complete(result)
            result
        } catch (error: Throwable) {
            pending.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(key, pending)
        }
    }

    private suspend fun getCachedOrLoad(
        videoUrl: String,
        key: String,
    ): ExtractionResult<List<VideoItem>> {
        cached(key)?.let { return ExtractionResult.Success(it) }
        val result = delegate.related(videoUrl)
        if (result is ExtractionResult.Success) store(key, result.data)
        return result
    }

    private suspend fun cached(key: String): List<VideoItem>? {
        val raw = try {
            cache.get(key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } ?: return null
        return runCatching { CacheJson.decodeFromString(VIDEO_LIST, raw) }.getOrNull()
    }

    private suspend fun store(key: String, videos: List<VideoItem>) {
        val value = CacheJson.encodeToString(VIDEO_LIST, videos)
        try {
            cache.set(key, value, CACHE_TTL_SECONDS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Related videos remain available even when the shared cache is unavailable.
        }
    }

    private companion object {
        const val CACHE_PREFIX = "youtube-related:v1"
        const val CACHE_TTL_SECONDS = 300L
    }
}
