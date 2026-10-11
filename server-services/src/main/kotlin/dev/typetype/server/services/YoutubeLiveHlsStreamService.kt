package dev.typetype.server.services

import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.models.StreamResponse

/**
 * A live stream is played from the manifest the live client gives; anything else from the
 * usual client. By default the live client is asked first for every video. That client cannot
 * run beside any other request (the extractor holds one player client for the whole process,
 * see YoutubePlayerClientScope), so every video opened waits for the previous one's probe:
 * about twenty a minute for a whole instance, and twice the requests to the provider.
 *
 * YOUTUBE_LIVE_PROBE=after asks the usual client first and the live client only for what
 * it says is live, or when it fails: the same answers, without the queue.
 */
class YoutubeLiveHlsStreamService(
    private val metadataService: StreamService,
    private val liveHlsService: StreamService,
    private val probeFirst: Boolean = System.getenv(PROBE_ENV)?.lowercase() != "after",
) : StreamService {
    override suspend fun getStreamInfo(url: String): ExtractionResult<StreamResponse> {
        if (!isYoutubeUrl(url)) return metadataService.getStreamInfo(url)
        if (probeFirst) return liveManifest(url) ?: metadataService.getStreamInfo(url)

        val metadata = metadataService.getStreamInfo(url)
        val known = (metadata as? ExtractionResult.Success)?.data
        if (known != null && !known.isLive) return metadata
        return liveManifest(url) ?: metadata
    }

    private suspend fun liveManifest(url: String): ExtractionResult<StreamResponse>? {
        val result = liveHlsService.getStreamInfo(url)
        val live = (result as? ExtractionResult.Success)?.data
        return result.takeIf { live?.isLive == true && !live.requiresMembership && live.hlsUrl.isNotBlank() }
    }

    private companion object {
        const val PROBE_ENV = "YOUTUBE_LIVE_PROBE"
    }
}
