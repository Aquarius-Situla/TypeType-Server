package dev.typetype.server.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeSessionPoToken
import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.models.StreamResponse
import dev.typetype.server.sabr.YoutubeSabrClientProfile
import dev.typetype.server.sabr.YoutubeSabrInfo
import dev.typetype.server.sabr.SabrAdapter
import org.slf4j.LoggerFactory

class AuthenticatedSabrInfoService(
    private val youtubeSessionService: YoutubeSessionService,
    private val tokenClient: TypetypeTokenSabrTokenClient,
    private val visitorDataFetcher: () -> String = AuthenticatedYoutubeVisitorData::fetch,
    private val probe: AuthenticatedSabrProbe = PipePipeAuthenticatedSabrProbe,
    private val cache: AuthenticatedSabrInfoCache = AuthenticatedSabrInfoCache(),
    private val tvClient: YoutubeTvOauthPlayerClient = YoutubeTvOauthPlayerClient(),
    private val bindingClient: TypetypeTokenYoutubeSessionClient? = null,
) {
    suspend fun fetch(userId: String?, videoId: String): AuthenticatedSabrInfoResult {
        if (userId == null || userId.startsWith("guest:")) return AuthenticatedSabrInfoResult.NotConnected
        if (youtubeSessionService.isOauthSession(userId)) return fetchOauth(userId, videoId)
        val credentials = youtubeSessionService.connectedCredentials(userId)
            ?: return AuthenticatedSabrInfoResult.NotConnected
        return try {
            cache.getOrLoad(credentials, videoId) { fetchUncached(credentials, videoId) }
        } catch (error: TimeoutCancellationException) {
            logger.warn("authenticated_sabr_probe event=timeout videoId={}", videoId)
            AuthenticatedSabrInfoResult.TimedOut
        }
    }

    private suspend fun fetchOauth(userId: String, videoId: String): AuthenticatedSabrInfoResult {
        val player = fetchOauthPlayer(userId, videoId) ?: return AuthenticatedSabrInfoResult.NotConnected
        val prepared = prepareOauth(userId, videoId, player)
        if (prepared is AuthenticatedSabrInfoResult.Ready) youtubeSessionService.markUsed(userId)
        return prepared
    }

    suspend fun oauthStream(userId: String, videoId: String): ExtractionResult<StreamResponse>? {
        if (!youtubeSessionService.isOauthSession(userId)) return null
        val player = fetchOauthPlayer(userId, videoId) ?: return null
        val prepared = prepareOauth(userId, videoId, player)
        if (prepared !is AuthenticatedSabrInfoResult.Ready) {
            return ExtractionResult.Failure("Authenticated SABR playback unavailable")
        }
        youtubeSessionService.markUsed(userId)
        return ExtractionResult.Success(player.response.toStreamResponse(videoId, prepared.prepared.info))
    }

    private suspend fun fetchOauthPlayer(userId: String, videoId: String): OauthPlayer? {
        val accessToken = youtubeSessionService.oauthAccessToken(userId)
        if (accessToken == null) {
            logger.warn("oauth_sabr_probe event=no_access_token videoId={}", videoId)
            return null
        }
        val tv = try {
            bindingClient?.fetchTvPoToken()
        } catch (error: Exception) {
            logger.warn("oauth_sabr_probe event=tv_token_error videoId={}", videoId)
            null
        } ?: return null
        return try {
            tvClient.player(
                videoId,
                accessToken,
                tv.visitorData,
                tv.livingRoomPoTokenId,
                tv.poToken,
            )?.let { OauthPlayer(accessToken, tv, it) }.also {
                if (it == null) logger.warn("oauth_sabr_probe event=empty_player videoId={}", videoId)
            }
        } catch (error: Exception) {
            logger.warn(
                "oauth_sabr_probe event=player_error videoId={} errorType={}",
                videoId,
                error::class.simpleName,
            )
            null
        }
    }

    private fun prepareOauth(
        userId: String,
        videoId: String,
        player: OauthPlayer,
    ): AuthenticatedSabrInfoResult {
        return try {
            val info = SabrAdapter.fromPlayerResponse(
                videoId,
                YoutubeSabrClientProfile.TVHTML5,
                player.response.cpn,
                player.response.response,
                player.tv.visitorData,
                player.response.clientVersion,
            )
            val prepared = SabrPreparedInfo(
                info,
                SabrTokenBundle.forSession(videoId, player.tv.poToken, player.tv.visitorData),
                source = SabrPreparedSource.AUTHENTICATED_YOUTUBE,
            )
            if (!prepared.hasAudioAndVideoFormats()) return AuthenticatedSabrInfoResult.Failed
            AuthenticatedSabrInfoResult.Ready(prepared)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("oauth_sabr_probe event=failed videoId={} errorType={}", videoId, error::class.simpleName)
            AuthenticatedSabrInfoResult.Failed
        }
    }

    private suspend fun fetchUncached(
        credentials: YoutubeSessionCredentials,
        videoId: String,
    ): AuthenticatedSabrInfoResult {
        return try {
            val prepared = YoutubeSessionTokenScope.withCredentials(credentials) {
                withContext(Dispatchers.IO) {
                    val sessionBinding = runInterruptible(Dispatchers.IO) { visitorDataFetcher() }
                    val token = runInterruptible(Dispatchers.IO) { tokenClient.fetchSession(videoId, sessionBinding) }
                        ?: error("Token service did not return authenticated SABR tokens")
                    val info = runInterruptible(Dispatchers.IO) { probe.fetch(videoId, token.youtubeSessionPoToken()) }
                    SabrPreparedInfo(
                        info = info,
                        initialToken = token,
                        source = SabrPreparedSource.AUTHENTICATED_YOUTUBE,
                    ).takeIf(SabrPreparedInfo::hasAudioAndVideoFormats)
                        ?: error("Authenticated SABR response has no audio and video formats")
                }
            }
            youtubeSessionService.markUsed(credentials.userId)
            AuthenticatedSabrInfoResult.Ready(prepared)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn(
                "authenticated_sabr_probe event=failed videoId={} errorType={} error={}",
                videoId,
                error.javaClass.simpleName,
                error.message,
            )
            AuthenticatedSabrInfoResult.Failed
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(AuthenticatedSabrInfoService::class.java)
    }
}

private fun TvPlayerResponse.toStreamResponse(
    videoId: String,
    info: YoutubeSabrInfo,
): StreamResponse {
    val details = response.getObject("videoDetails")
    val lengthSeconds = details.getString("lengthSeconds", "0").toLongOrNull() ?: 0L
    return StreamResponse(
        id = videoId,
        title = details.getString("title", ""),
        uploaderName = details.getString("author", ""),
        uploaderUrl = details.getString("channelId", "")
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

sealed interface AuthenticatedSabrInfoResult {
    data object NotConnected : AuthenticatedSabrInfoResult
    data object Failed : AuthenticatedSabrInfoResult
    data object TimedOut : AuthenticatedSabrInfoResult
    data class Ready(val prepared: SabrPreparedInfo) : AuthenticatedSabrInfoResult
}

fun interface AuthenticatedSabrProbe {
    fun fetch(videoId: String, token: YoutubeSessionPoToken): YoutubeSabrInfo
}

private object PipePipeAuthenticatedSabrProbe : AuthenticatedSabrProbe {
    private val localization = Localization("en", "US")
    private val contentCountry = ContentCountry("US")

    override fun fetch(videoId: String, token: YoutubeSessionPoToken): YoutubeSabrInfo =
        SabrAdapter.fetchSabrInfo(
            videoId,
            YoutubeSabrClientProfile.WEB,
            localization,
            contentCountry,
            token.poToken,
            token.visitorData,
        )
}

private data class OauthPlayer(
    val accessToken: String,
    val tv: TokenTvPoToken,
    val response: TvPlayerResponse,
)
