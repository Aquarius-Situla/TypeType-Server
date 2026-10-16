package dev.typetype.server.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

internal class BiliBiliCookieRefreshClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun inspectAndRotate(cookies: String, refreshToken: String): BiliBiliCookieRefreshResult =
        withContext(Dispatchers.IO) {
            try {
                inspectAndRotateBlocking(cookies, refreshToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BiliBiliCookieRefreshResult.RetryableFailure
            }
        }

    suspend fun confirmRotation(cookies: String, oldRefreshToken: String): BiliBiliCookieRefreshConfirmation =
        withContext(Dispatchers.IO) {
            try {
                confirmRotationBlocking(cookies, oldRefreshToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BiliBiliCookieRefreshConfirmation.RetryableFailure
            }
        }

    private fun inspectAndRotateBlocking(cookies: String, refreshToken: String): BiliBiliCookieRefreshResult {
        val info = jsonCall(Request.Builder().url(COOKIE_INFO_URL).header("Cookie", cookies).build())
        if (!info.successful) return BiliBiliCookieRefreshResult.RetryableFailure
        val infoRoot = info.body ?: return BiliBiliCookieRefreshResult.RetryableFailure
        when (infoRoot.code()) {
            -101 -> return BiliBiliCookieRefreshResult.NeedsReconnect
            0 -> Unit
            else -> return BiliBiliCookieRefreshResult.RetryableFailure
        }
        val infoData = infoRoot.objectField("data") ?: return BiliBiliCookieRefreshResult.RetryableFailure
        val shouldRefresh = infoData["refresh"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: return BiliBiliCookieRefreshResult.RetryableFailure
        if (!shouldRefresh) return BiliBiliCookieRefreshResult.NotRequired
        val timestamp = infoData["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?.takeIf { it > 0 } ?: return BiliBiliCookieRefreshResult.RetryableFailure

        val path = BiliBiliCookieRefreshCrypto.correspondPath(timestamp)
        val correspondRequest = Request.Builder()
            .url("$CORRESPOND_URL/$path")
            .header("Cookie", cookies)
            .header("User-Agent", BiliBiliQrLoginService.WEB_USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .build()
        val html = client.newCall(correspondRequest).execute().use { response ->
            if (!response.isSuccessful) return BiliBiliCookieRefreshResult.RetryableFailure
            response.body?.string() ?: return BiliBiliCookieRefreshResult.RetryableFailure
        }
        val refreshCsrf = BiliBiliCookieRefreshCrypto.refreshCsrf(html)
            ?: return BiliBiliCookieRefreshResult.RetryableFailure
        val csrf = cookieValue(cookies, "bili_jct")
            ?: return BiliBiliCookieRefreshResult.NeedsReconnect
        val refreshRequest = Request.Builder()
            .url(COOKIE_REFRESH_URL)
            .header("Cookie", cookies)
            .header("User-Agent", BiliBiliQrLoginService.WEB_USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .post(FormBody.Builder()
                .add("csrf", csrf)
                .add("refresh_csrf", refreshCsrf)
                .add("source", "main_web")
                .add("refresh_token", refreshToken)
                .build())
            .build()
        val refreshed = jsonCall(refreshRequest)
        if (!refreshed.successful) return BiliBiliCookieRefreshResult.RetryableFailure
        val refreshRoot = refreshed.body ?: return BiliBiliCookieRefreshResult.RetryableFailure
        when (refreshRoot.code()) {
            -101, -111, 86095 -> return BiliBiliCookieRefreshResult.NeedsReconnect
            0 -> Unit
            else -> return BiliBiliCookieRefreshResult.RetryableFailure
        }
        val data = refreshRoot.objectField("data") ?: return BiliBiliCookieRefreshResult.RetryableFailure
        val newRefreshToken = data["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_REFRESH_TOKEN_LENGTH }
            ?: return BiliBiliCookieRefreshResult.RetryableFailure
        val mergedCookies = (cookieMap(cookies) + cookieMap(refreshed.setCookies)).toCookieHeader()
        if (cookieValue(mergedCookies, "SESSDATA") == null || cookieValue(mergedCookies, "bili_jct") == null) {
            return BiliBiliCookieRefreshResult.NeedsReconnect
        }
        val expiresAt = sessDataExpiry(mergedCookies) ?: nowMillis() + DEFAULT_COOKIE_TTL_MS
        return BiliBiliCookieRefreshResult.Rotated(mergedCookies, newRefreshToken, expiresAt)
    }

    private fun confirmRotationBlocking(cookies: String, oldRefreshToken: String): BiliBiliCookieRefreshConfirmation {
        val csrf = cookieValue(cookies, "bili_jct") ?: return BiliBiliCookieRefreshConfirmation.NeedsReconnect
        val request = Request.Builder()
            .url(CONFIRM_REFRESH_URL)
            .header("Cookie", cookies)
            .header("User-Agent", BiliBiliQrLoginService.WEB_USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .post(FormBody.Builder().add("csrf", csrf).add("refresh_token", oldRefreshToken).build())
            .build()
        val response = jsonCall(request)
        if (!response.successful) return BiliBiliCookieRefreshConfirmation.RetryableFailure
        val root = response.body ?: return BiliBiliCookieRefreshConfirmation.RetryableFailure
        return when (root.code()) {
            0 -> BiliBiliCookieRefreshConfirmation.Confirmed
            -101, -111 -> verifyRotatedSession(cookies)
            else -> BiliBiliCookieRefreshConfirmation.RetryableFailure
        }
    }

    private fun verifyRotatedSession(cookies: String): BiliBiliCookieRefreshConfirmation {
        val request = Request.Builder()
            .url(COOKIE_INFO_URL)
            .header("Cookie", cookies)
            .header("User-Agent", BiliBiliQrLoginService.WEB_USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .build()
        val response = jsonCall(request)
        if (!response.successful) return BiliBiliCookieRefreshConfirmation.RetryableFailure
        val root = response.body ?: return BiliBiliCookieRefreshConfirmation.RetryableFailure
        return when (root.code()) {
            -101 -> BiliBiliCookieRefreshConfirmation.NeedsReconnect
            0 -> {
                val data = root.objectField("data") ?: return BiliBiliCookieRefreshConfirmation.RetryableFailure
                val refresh = data["refresh"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: return BiliBiliCookieRefreshConfirmation.RetryableFailure
                if (refresh) BiliBiliCookieRefreshConfirmation.RetryableFailure
                else BiliBiliCookieRefreshConfirmation.Confirmed
            }
            else -> BiliBiliCookieRefreshConfirmation.RetryableFailure
        }
    }

    private fun jsonCall(request: Request): JsonHttpResponse = client.newCall(request).execute().use { response ->
        val body = response.body?.string()?.let { raw ->
            runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        }
        JsonHttpResponse(response.isSuccessful, response.headers("Set-Cookie"), body)
    }

    private fun JsonObject.code(): Int? = this["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun JsonObject.objectField(name: String): JsonObject? = this[name] as? JsonObject

    private fun cookieMap(headers: List<String>): Map<String, String> = headers.mapNotNull { header ->
        val pair = header.substringBefore(';').trim().split('=', limit = 2)
        if (pair.size != 2) return@mapNotNull null
        val name = pair[0].trim()
        val value = pair[1].trim()
        if (name !in COOKIE_NAMES || value.isEmpty()) null else name to value
    }.toMap()

    private fun cookieMap(header: String): Map<String, String> = cookieMap(header.split(';'))

    private fun Map<String, String>.toCookieHeader(): String = COOKIE_NAMES.mapNotNull { name ->
        this[name]?.let { "$name=$it" }
    }.joinToString("; ")

    private fun cookieValue(header: String, name: String): String? = cookieMap(header)[name]

    private fun sessDataExpiry(cookieHeader: String): Long? = cookieValue(cookieHeader, "SESSDATA")
        ?.split(',')?.getOrNull(1)?.toLongOrNull()?.takeIf { it >= MIN_SESSDATA_TIMESTAMP }
        ?.times(1_000)

    private data class JsonHttpResponse(val successful: Boolean, val setCookies: List<String>, val body: JsonObject?)

    private companion object {
        const val COOKIE_INFO_URL = "https://passport.bilibili.com/x/passport-login/web/cookie/info"
        const val COOKIE_REFRESH_URL = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh"
        const val CONFIRM_REFRESH_URL = "https://passport.bilibili.com/x/passport-login/web/confirm/refresh"
        const val CORRESPOND_URL = "https://www.bilibili.com/correspond/1"
        const val MAX_REFRESH_TOKEN_LENGTH = 512
        const val MIN_SESSDATA_TIMESTAMP = 1_000_000_000L
        const val DEFAULT_COOKIE_TTL_MS = 30L * 24 * 60 * 60 * 1_000
        val COOKIE_NAMES = setOf("SESSDATA", "bili_jct", "buvid3", "DedeUserID", "DedeUserID__ckMd5", "sid")
    }
}

internal sealed interface BiliBiliCookieRefreshResult {
    data object NotRequired : BiliBiliCookieRefreshResult
    data object NeedsReconnect : BiliBiliCookieRefreshResult
    data object RetryableFailure : BiliBiliCookieRefreshResult
    data class Rotated(val cookies: String, val refreshToken: String, val expiresAt: Long) : BiliBiliCookieRefreshResult
}

internal sealed interface BiliBiliCookieRefreshConfirmation {
    data object Confirmed : BiliBiliCookieRefreshConfirmation
    data object NeedsReconnect : BiliBiliCookieRefreshConfirmation
    data object RetryableFailure : BiliBiliCookieRefreshConfirmation
}
