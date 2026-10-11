package dev.typetype.server.services

import dev.typetype.server.models.ExtractionFailureKind
import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.models.VideoItem

class RecommenderSourceLoader(
    private val searchService: SearchService,
    private val channelService: ChannelService,
    private val relatedVideos: RelatedVideosService,
    private val trendingService: TrendingService,
    private val sourceCache: RecommenderSourceCache,
    private val limiter: RecommenderStreamLimiter,
    private val metrics: RecommenderMetrics,
    private val config: RecommenderConfig,
) {
    suspend fun search(query: String): List<VideoItem> =
        sourceCache.getOrLoad("search:$query", config.searchTtlSeconds) {
            searchService.search(query, YOUTUBE_SERVICE_ID).dataOrNull()?.items.orEmpty()
        }

    suspend fun channelVideos(channelUrl: String, sort: String?): List<VideoItem> =
        sourceCache.getOrLoad("channel:${sort.orEmpty()}:$channelUrl", config.channelTtlSeconds) {
            channelService.getChannel(channelUrl, null, sort).dataOrNull()?.videos.orEmpty()
        }

    suspend fun trending(): List<VideoItem> =
        sourceCache.getOrLoad("trending", config.channelTtlSeconds) {
            trendingService.getTrending(YOUTUBE_SERVICE_ID).dataOrNull().orEmpty()
        }

    suspend fun related(videoUrl: String): List<VideoItem> =
        sourceCache.getOrLoad("related:$videoUrl", config.relatedTtlSeconds) {
            if (!limiter.tryAcquire()) {
                metrics.streamRequestsRefused.incrementAndGet()
                return@getOrLoad emptyList()
            }
            metrics.streamRequests.incrementAndGet()
            val result = relatedVideos.related(videoUrl)
            limiter.opened(alreadyCounted = true, blocked = result.isProviderRefusal())
            result.dataOrNull().orEmpty().filter { it.url.isNotBlank() && it.url != videoUrl }
        }

    private fun ExtractionResult<*>.isProviderRefusal(): Boolean =
        (this as? ExtractionResult.Failure)?.kind == ExtractionFailureKind.ProviderAccessBlocked

    private fun <T> ExtractionResult<T>.dataOrNull(): T? = (this as? ExtractionResult.Success<T>)?.data
}
