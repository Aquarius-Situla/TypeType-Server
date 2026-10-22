package dev.typetype.server.services

import dev.typetype.server.models.StreamResponse
import dev.typetype.server.sabr.YoutubeSabrInfo

internal fun TvPlayerResponse.toStreamResponse(
    videoId: String,
    info: YoutubeSabrInfo,
): StreamResponse {
    val details = response.getObject("videoDetails")
    val lengthSeconds = details.getString("lengthSeconds", "0").toLongOrNull() ?: 0L
    return StreamResponse(
        id = videoId,
        title = response.youtubeTvTitle(),
        uploaderName = response.youtubeTvUploaderName(),
        uploaderUrl = response.youtubeTvChannelId()
            .takeIf { it.isNotBlank() }
            ?.let { "https://www.youtube.com/channel/$it" }
            .orEmpty(),
        uploaderAvatarUrl = "",
        thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hq720.jpg",
        description = details.getString("shortDescription", ""),
        duration = lengthSeconds,
        viewCount = details.getString("viewCount", "-1").toLongOrNull() ?: -1L,
        likeCount = -1L,
        dislikeCount = -1L,
        uploadDate = "",
        uploaded = -1L,
        uploaderSubscriberCount = -1L,
        uploaderVerified = false,
        category = "",
        license = "",
        visibility = "public",
        tags = emptyList(),
        streamType = if (details.getBoolean("isLiveContent", false)) "live_stream" else "video_stream",
        isLive = details.getBoolean("isLive", false),
        isPostLive = false,
        isLiveContent = details.getBoolean("isLiveContent", false),
        hasLiveManifest = false,
        isShortFormContent = false,
        requiresMembership = false,
        startPosition = 0L,
        streamSegments = emptyList(),
        hlsUrl = "",
        dashMpdUrl = "",
        videoStreams = emptyList(),
        audioStreams = emptyList(),
        originalAudioTrackId = null,
        preferredDefaultAudioTrackId = null,
        videoOnlyStreams = emptyList(),
        subtitles = emptyList(),
        previewFrames = emptyList(),
        sponsorBlockSegments = emptyList(),
        relatedStreams = emptyList(),
    ).withSabrFallback(videoId, info)
}
