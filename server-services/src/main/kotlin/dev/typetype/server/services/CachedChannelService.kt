package dev.typetype.server.services

import dev.typetype.server.cache.CacheService
import dev.typetype.server.models.ChannelPlaylistsResponse
import dev.typetype.server.models.ChannelResponse
import dev.typetype.server.models.ExtractionResult

class CachedChannelService(
    private val delegate: ChannelService,
    private val cache: CacheService,
) : ChannelService {

    override suspend fun getChannel(url: String, nextpage: String?, sort: String?): ExtractionResult<ChannelResponse> {
        val isBilibili = url.isBilibiliChannelUrl()
        val cacheArea = if (isBilibili) BILIBILI_CHANNEL_CACHE_AREA else "channel"
        return PublicExtractionCache.getOrLoad(
            cache = cache,
            area = cacheArea,
            key = PublicCacheKey.of(cacheArea, url, nextpage, sort),
            serializer = ChannelResponse.serializer(),
            ttlSeconds = { response ->
                if (isBilibili && nextpage == null && response.videos.isEmpty()) {
                    BILIBILI_EMPTY_CHANNEL_TTL_SECONDS
                } else {
                    PublicCachePolicy.channelTtl(url, nextpage, sort)
                }
            },
        ) { delegate.getChannel(url, nextpage, sort) }
    }

    override suspend fun getPlaylists(url: String, nextpage: String?): ExtractionResult<ChannelPlaylistsResponse> =
        PublicExtractionCache.getOrLoad(
            cache = cache,
            area = "channel-playlists",
            key = PublicCacheKey.of("channel-playlists", url, nextpage),
            serializer = ChannelPlaylistsResponse.serializer(),
            ttlSeconds = { PublicCachePolicy.channelTtl(url, nextpage, null) },
        ) { delegate.getPlaylists(url, nextpage) }

    private fun String.isBilibiliChannelUrl(): Boolean = recommendationServiceId(this) == BILIBILI_SERVICE_ID

    private companion object {
        const val BILIBILI_CHANNEL_CACHE_AREA = "channel-bilibili-v3"
        const val BILIBILI_EMPTY_CHANNEL_TTL_SECONDS = 30L
    }
}
