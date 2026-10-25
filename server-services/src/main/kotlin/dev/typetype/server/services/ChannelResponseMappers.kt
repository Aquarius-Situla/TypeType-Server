package dev.typetype.server.services

import dev.typetype.server.models.ChannelPlaylistsResponse
import dev.typetype.server.models.ChannelResponse
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelTabInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.exceptions.ExtractionException

fun ChannelInfo.toChannelResponse(): ChannelResponse = ChannelResponse(
    name = name ?: "",
    description = description ?: "",
    avatarUrl = avatarUrl ?: "",
    bannerUrl = bannerUrl ?: "",
    subscriberCount = subscriberCount,
    isVerified = isVerified,
    videos = relatedItems.map { it.toVideoItem(fallbackAvatarUrl = avatarUrl ?: "") },
    nextpage = nextPage?.toCursor(),
)

fun InfoItemsPage<StreamInfoItem>.toChannelResponse(): ChannelResponse = ChannelResponse(
    name = "",
    description = "",
    avatarUrl = "",
    bannerUrl = "",
    subscriberCount = -1L,
    isVerified = false,
    videos = items.map { it.toVideoItem() },
    nextpage = nextPage?.toCursor(),
)

fun ChannelTabInfo.toChannelTabResponse(metadata: ChannelInfo? = null): ChannelResponse {
    val videoItems = relatedItems.filterIsInstance<StreamInfoItem>()
    if (videoItems.isEmpty() && errors.isNotEmpty()) {
        throw ExtractionException("Channel extraction returned no videos", errors.first())
    }
    return ChannelResponse(
        name = metadata?.name ?: name ?: "",
        description = metadata?.description ?: "",
        avatarUrl = metadata?.avatarUrl ?: "",
        bannerUrl = metadata?.bannerUrl ?: "",
        subscriberCount = metadata?.subscriberCount ?: -1L,
        isVerified = metadata?.isVerified ?: false,
        videos = videoItems.map { it.toVideoItem(fallbackAvatarUrl = metadata?.avatarUrl ?: "") },
        nextpage = nextPage?.toCursor(),
    )
}

fun InfoItemsPage<InfoItem>.toChannelTabResponse(): ChannelResponse = ChannelResponse(
    name = "",
    description = "",
    avatarUrl = "",
    bannerUrl = "",
    subscriberCount = -1L,
    isVerified = false,
    videos = items.filterIsInstance<StreamInfoItem>().map { it.toVideoItem() },
    nextpage = nextPage?.toCursor(),
)

fun ChannelTabInfo.toChannelPlaylistsResponse(): ChannelPlaylistsResponse = ChannelPlaylistsResponse(
    playlists = relatedItems.filterIsInstance<PlaylistInfoItem>().map { it.toPlaylistResultItem() },
    nextpage = nextPage?.toCursor(),
)

fun InfoItemsPage<InfoItem>.toChannelPlaylistsResponse(): ChannelPlaylistsResponse = ChannelPlaylistsResponse(
    playlists = items.filterIsInstance<PlaylistInfoItem>().map { it.toPlaylistResultItem() },
    nextpage = nextPage?.toCursor(),
)
