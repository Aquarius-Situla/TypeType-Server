package dev.typetype.server.services

import dev.typetype.server.cache.CacheService
import dev.typetype.server.models.ChannelResponse
import dev.typetype.server.models.ExtractionResult

class CachedChannelVideosSource(
    private val channelService: ChannelService,
    private val tabVideos: ChannelVideosSource,
    private val cache: CacheService,
) : ChannelVideosSource {
    override suspend fun firstPage(url: String): ExtractionResult<ChannelResponse> {
        if (url.toChannelTab(null) == null) return channelService.getChannel(url, null)
        return PublicExtractionCache.getOrLoad(
            cache = cache,
            area = CACHE_AREA,
            key = PublicCacheKey.of(CACHE_AREA, url),
            serializer = ChannelResponse.serializer(),
            ttlSeconds = { PublicCachePolicy.feedTabTtl(url) },
        ) { tabVideos.firstPage(url) }
    }

    private companion object {
        const val CACHE_AREA = "channel-videos"
    }
}
