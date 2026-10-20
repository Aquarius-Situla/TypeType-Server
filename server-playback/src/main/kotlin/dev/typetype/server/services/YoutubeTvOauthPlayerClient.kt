package dev.typetype.server.services

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Builds the TV client player request used by the device-code session. The request shape
 * follows the TV client verified against YouTube: an 8-digit signature timestamp is
 * required or the player answers "The page needs to be reloaded."
 */
class YoutubeTvOauthPlayerClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var signatureTimestamp: Pair<Long, Long>? = null
    private var clientVersion: String = DEFAULT_CLIENT_VERSION

    suspend fun player(videoId: String, accessToken: String): TvPlayerResponse? = withContext(Dispatchers.IO) {
        try {
            val timestamp = signatureTimestamp() ?: return@withContext null
            val cpn = YoutubeParsingHelper.generateContentPlaybackNonce()
            val body = JsonObject.builder()
                .`object`("context")
                .`object`("client")
                .value("clientName", CLIENT_NAME)
                .value("clientVersion", clientVersion)
                .value("hl", "en")
                .value("gl", "US")
                .value("deviceMake", "Google")
                .value("deviceModel", "Chromecast")
                .value("osName", "Android")
                .value("osVersion", "10")
                .value("platform", "TV")
                .value("utcOffsetMinutes", 0)
                .end()
                .end()
                .`object`("playbackContext")
                .`object`("contentPlaybackContext")
                .value("html5Preference", "HTML5_PREF_WANTS")
                .value("lactMilliseconds", 60_000)
                .value("isInlinePlaybackNoAd", true)
                .value("signatureTimestamp", timestamp)
                .end()
                .end()
                .value("videoId", videoId)
                .value("cpn", cpn)
                .value("racyCheckOk", true)
                .value("contentCheckOk", true)
                .done()
                .let { JsonWriter.string(it) }
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url(PLAYER_URL)
                .header("Authorization", "Bearer $accessToken")
                .header("X-Youtube-Client-Name", TV_CLIENT_ID)
                .header("X-Youtube-Client-Version", clientVersion)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .header("User-Agent", TV_USER_AGENT)
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    null
                } else {
                    TvPlayerResponse(cpn, JsonParser.`object`().from(text))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun signatureTimestamp(): Long? {
        val cached = signatureTimestamp
        val now = nowMillis()
        if (cached != null && now - cached.first < TIMESTAMP_TTL_MS) return cached.second
        return try {
            val value = tvSignature(now) ?: webSignature(now) ?: return null
            signatureTimestamp = now to value
            value
        } catch (_: Exception) {
            null
        }
    }

    private fun tvSignature(now: Long): Long? {
        val page = fetchText(TV_PAGE_URL, TV_USER_AGENT) ?: return null
        val playerUrl = PLAYER_URL_REGEX.find(page)?.groupValues?.get(1)?.replace("\\/", "/") ?: return null
        val script = fetchText(
            if (playerUrl.startsWith("/")) "https://www.youtube.com$playerUrl" else playerUrl,
            TV_USER_AGENT,
        ) ?: return null
        val digits = SIGNATURE_REGEX.find(script)?.groupValues?.get(1) ?: return null
        TV_VERSION_REGEX.find(page)?.groupValues?.get(1)?.let { clientVersion = it }
        return if (digits.length == 5) "${digits}001".toLong() else digits.toLong()
    }

    private fun webSignature(now: Long): Long? {
        val page = fetchText(WATCH_PROBE_URL, WEB_USER_AGENT) ?: return null
        val playerUrl = WEB_JS_REGEX.find(page)?.groupValues?.get(1) ?: return null
        val script = fetchText(
            if (playerUrl.startsWith("/")) "https://www.youtube.com$playerUrl" else playerUrl,
            WEB_USER_AGENT,
        ) ?: return null
        val digits = SIGNATURE_REGEX.find(script)?.groupValues?.get(1) ?: return null
        return if (digits.length == 5) "${digits}001".toLong() else digits.toLong()
    }

    private fun fetchText(url: String, userAgent: String): String? =
        client.newCall(
            Request.Builder().url(url).header("User-Agent", userAgent).get().build(),
        ).execute().use { response ->
            val body = if (response.isSuccessful) response.body?.string() else null
            logger.warn(
                "tv_player_fetch host={} status={} bytes={}",
                url.substringAfter("//").substringBefore("/"),
                response.code,
                body?.length ?: 0,
            )
            body
        }

    private companion object {
        val logger = org.slf4j.LoggerFactory.getLogger(YoutubeTvOauthPlayerClient::class.java)
        const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
        const val TV_PAGE_URL = "https://www.youtube.com/tv"
        const val CLIENT_NAME = "TVHTML5"
        const val TV_CLIENT_ID = "7"
        const val DEFAULT_CLIENT_VERSION = "7.20261014.18.00"
        const val TIMESTAMP_TTL_MS = 6 * 60 * 60 * 1000L
        const val TV_USER_AGENT =
            "Mozilla/5.0 (SMART-TV; X11; Linux armv7l) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.85 Safari/537.36"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
        const val WATCH_PROBE_URL =
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&bpctr=9999999999&has_verified=1"
        val JSON = "application/json".toMediaType()
        val PLAYER_URL_REGEX = Regex("""player_url":"([^"]+)"""")
        val WEB_JS_REGEX = Regex(""""jsUrl":"([^"]+)"""")
        val SIGNATURE_REGEX = Regex("""signatureTimestamp:(\d+)""")
        val TV_VERSION_REGEX = Regex(""""INNERTUBE_CLIENT_VERSION":"([^"]+)"""")
    }
}

data class TvPlayerResponse(
    val cpn: String,
    val response: JsonObject,
)
