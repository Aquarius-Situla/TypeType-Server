package dev.typetype.server.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import dev.typetype.server.PLAYBACK_TRACE_ID_HEADER
import dev.typetype.server.REQUEST_ID_HEADER
import dev.typetype.server.PlaybackTraceLog
import dev.typetype.server.currentPlaybackTraceId
import dev.typetype.server.currentRequestId
import dev.typetype.server.sabr.YoutubeSabrInfo
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume

class TypetypeTokenYoutubeSessionClient(
    private val tokenServiceUrl: String,
    client: OkHttpClient = OkHttpClient(),
) {
    internal val client: OkHttpClient = TypetypeTokenHttpClient.bounded(client)

    suspend fun importSessionBinding(cookies: String): TokenSessionBinding? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("cookies", cookies).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${tokenServiceUrl.trimEnd('/')}/session-binding")
                .post(body)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        null
                    } else {
                        val payload = JSONObject(response.body?.string().orEmpty())
                        TokenSessionBinding(
                            cookies = payload.optString("cookies"),
                            poToken = payload.optString("poToken"),
                            authUser = payload.optInt("authUser"),
                        ).takeIf { it.cookies.isNotBlank() && it.poToken.isNotBlank() }
                    }
                }
            } catch (_: IOException) {
                null
            }
        }

    suspend fun startDeviceLogin(accountKey: String): TokenDeviceStart? =
        deviceCall("/device/start", accountKey, "accountKey")?.let { payload ->
            TokenDeviceStart(
                userCode = payload.optString("userCode"),
                verificationUrl = payload.optString("verificationUrl"),
                expiresAt = payload.optLong("expiresAt"),
                intervalSeconds = payload.optInt("intervalSeconds"),
            ).takeIf { it.userCode.isNotBlank() && it.verificationUrl.isNotBlank() }
        }

    suspend fun pollDeviceLogin(accountKey: String): TokenDevicePoll? =
        deviceCall("/device/poll", accountKey, "accountKey")?.let { payload ->
            TokenDevicePoll(
                status = payload.optString("status"),
                tokens = payload.optJSONObject("tokens")?.let { tokens ->
                    TokenDeviceTokens(
                        accessToken = tokens.optString("accessToken"),
                        refreshToken = tokens.optString("refreshToken").ifBlank { null },
                        expiresIn = tokens.optLong("expiresIn"),
                    )
                },
            )
        }

    suspend fun refreshDeviceToken(refreshToken: String): TokenDeviceTokens? =
        deviceCall("/device/refresh", refreshToken, "refreshToken")?.let { payload ->
            TokenDeviceTokens(
                accessToken = payload.optString("accessToken"),
                refreshToken = payload.optString("refreshToken").ifBlank { refreshToken },
                expiresIn = payload.optLong("expiresIn"),
            ).takeIf { it.accessToken.isNotBlank() }
        }

    suspend fun revokeDeviceToken(token: String) {
        deviceCall("/device/revoke", token, "token")
    }

    suspend fun fetchTvPoToken(): TokenTvPoToken? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${tokenServiceUrl.trimEnd('/')}/tv-token")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val payload = JSONObject(response.body?.string().orEmpty())
                TokenTvPoToken(
                    poToken = payload.optString("poToken"),
                    visitorData = payload.optString("visitorData"),
                    livingRoomPoTokenId = payload.optString("livingRoomPoTokenId"),
                ).takeIf {
                    it.poToken.isNotBlank() &&
                        it.visitorData.isNotBlank() &&
                        it.livingRoomPoTokenId.isNotBlank()
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    suspend fun cancelDeviceLogin(accountKey: String) {
        deviceCall("/device/cancel", accountKey, "accountKey")
    }

    private suspend fun deviceCall(path: String, value: String, field: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put(field, value).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${tokenServiceUrl.trimEnd('/')}$path")
                .post(body)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val text = response.body?.string().orEmpty()
                    if (text.isBlank()) JSONObject() else JSONObject(text)
                }
            } catch (_: IOException) {
                null
            }
        }

    suspend fun fetchHlsManifestUrl(videoId: String): String? = fetchSession(videoId)
        ?.optString("hlsManifestUrl")
        ?.trim()
        ?.ifBlank { null }

    suspend fun fetchSabrInfo(videoId: String): YoutubeSabrInfo? = fetchSession(videoId)?.let { session ->
        session.toSabrInfo(videoId)
    }

    suspend fun fetchPlaybackSession(
        videoId: String,
        isolated: Boolean = false,
    ): TokenYoutubeSession? = fetchSession(videoId, isolated)?.let { session ->
        session.toPlaybackSession(videoId)
    }

    private suspend fun fetchSession(videoId: String, isolated: Boolean = false): JSONObject? {
        val encodedVideoId = URLEncoder.encode(videoId, StandardCharsets.UTF_8)
        val url = "${tokenServiceUrl.trimEnd('/')}/youtube/sabr/session?videoId=$encodedVideoId&client=MWEB" +
            if (isolated) "&isolated=true" else ""
        return suspendCancellableCoroutine { continuation ->
            val requestBuilder = Request.Builder().url(url).get()
            currentRequestId()?.let { requestBuilder.header(REQUEST_ID_HEADER, it) }
            currentPlaybackTraceId()?.let { requestBuilder.header(PLAYBACK_TRACE_ID_HEADER, it) }
            val traceId = currentPlaybackTraceId()
            val requestId = currentRequestId()
            val startedAt = System.nanoTime()
            PlaybackTraceLog.record("token_session_start", "operation=youtube_sabr_session isolated=$isolated")
            val call = client.newCall(requestBuilder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    PlaybackTraceLog.record(traceId, requestId, "token_session_complete", "status=network_error durationMs=${(System.nanoTime() - startedAt) / 1_000_000}")
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val status = response.code
                    val session = response.use {
                        if (!it.isSuccessful) null else runCatching { JSONObject(it.body.string()) }.getOrNull()
                    }
                    PlaybackTraceLog.record(traceId, requestId, "token_session_complete", "status=$status result=${if (session == null) "empty" else "ok"} durationMs=${(System.nanoTime() - startedAt) / 1_000_000}")
                    if (continuation.isActive) continuation.resume(session)
                }
            })
        }
    }
}
