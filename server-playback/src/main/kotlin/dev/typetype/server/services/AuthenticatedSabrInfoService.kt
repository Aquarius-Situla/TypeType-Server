package dev.typetype.server.services

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
            val responseVisitorData = player.response.response
                .getObject("responseContext")
                ?.getString("visitorData")
                ?.takeIf(String::isNotBlank)
                ?: player.tv.visitorData
            val info = SabrAdapter.fromPlayerResponse(
                videoId,
                YoutubeSabrClientProfile.TVHTML5,
                player.response.cpn,
                player.response.response,
                responseVisitorData,
                player.response.clientVersion,
            )
            val infoWithPot = SabrAdapter.withPlaybackIdentity(
                info,
                requireNotNull(info.serverAbrStreamingUrl).withPoToken(player.tv.poToken),
                player.response.clientVersion,
                player.response.cpn,
                responseVisitorData,
            )
            val prepared = SabrPreparedInfo(
                infoWithPot,
                SabrTokenBundle.forSession(videoId, player.tv.poToken, responseVisitorData),
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

sealed interface AuthenticatedSabrInfoResult {
    data object NotConnected : AuthenticatedSabrInfoResult
    data object Failed : AuthenticatedSabrInfoResult
    data object TimedOut : AuthenticatedSabrInfoResult
    data class Ready(val prepared: SabrPreparedInfo) : AuthenticatedSabrInfoResult
}

private fun String.withPoToken(poToken: String): String =
    requireNotNull(toHttpUrlOrNull()) { "Invalid SABR playback URL" }
        .newBuilder()
        .setQueryParameter("pot", poToken)
        .build()
        .toString()

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
