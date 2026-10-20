package dev.typetype.server.services

import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.models.StreamResponse
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

const val YOUTUBE_SESSION_REQUIRED_CODE = "youtube_session_required"
const val YOUTUBE_SESSION_REQUIRED_ERROR = "Connect YouTube to access this video"

class YoutubeSessionSabrStreamService(
    private val metadataService: YoutubeSessionStreamService,
    private val infoService: AuthenticatedSabrInfoService,
    private val timeoutMs: Long = AuthenticatedSabrPolicy.STREAM_TIMEOUT_MS,
) {
    suspend fun getStreamInfo(userId: String, url: String): ExtractionResult<StreamResponse>? {
        return try {
            withTimeout(timeoutMs) {
                val metadata = metadataService.getStreamInfo(userId, url)
                if (metadata != null && metadata !is ExtractionResult.Success) {
                    return@withTimeout metadata
                }
                val data = (metadata as? ExtractionResult.Success)?.data
                if (data?.isLive == true) return@withTimeout null
                val videoId = youtubeVideoId(url) ?: return@withTimeout ExtractionResult.BadRequest("Invalid YouTube URL")
                infoService.oauthStream(userId, videoId)?.let { return@withTimeout it }
                if (data == null) return@withTimeout null
                when (val info = infoService.fetch(userId, videoId)) {
                    is AuthenticatedSabrInfoResult.Ready ->
                        ExtractionResult.Success(data.withSabrFallback(videoId, info.prepared.info))
                    AuthenticatedSabrInfoResult.Failed ->
                        ExtractionResult.Failure("Authenticated SABR playback unavailable")
                    AuthenticatedSabrInfoResult.TimedOut ->
                        reconnectResult(userId)
                    AuthenticatedSabrInfoResult.NotConnected -> null
                }
            }
        } catch (error: TimeoutCancellationException) {
            reconnectResult(userId)
        }
    }

    private suspend fun reconnectResult(userId: String): ExtractionResult<StreamResponse> {
        metadataService.markYoutubeSessionNeedsReconnect(userId)
        return ExtractionResult.BadRequest(YOUTUBE_SESSION_RECONNECT_ERROR, YOUTUBE_SESSION_RECONNECT_CODE)
    }
}

fun ExtractionResult<StreamResponse>.requiresYoutubeSession(): Boolean =
    when (this) {
        is ExtractionResult.Success -> data.requiresMembership
        is ExtractionResult.BadRequest -> code == "age_restricted" || code == "members_only"
        is ExtractionResult.Failure -> false
    }
