package dev.typetype.server.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class BiliBiliQrLoginService(
    private val client: OkHttpClient = OkHttpClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(): BiliBiliQrGenerateResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(GENERATE_URL)
            .header("User-Agent", WEB_USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .build()
        val response = client.newCall(request).execute()
        response.use {
            val body = it.body.string() ?: return@withContext BiliBiliQrGenerateResult.Error("Empty response")
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return@withContext BiliBiliQrGenerateResult.Error("Invalid JSON")
            val data = root["data"]?.jsonObject
                ?: return@withContext BiliBiliQrGenerateResult.Error("Missing data")
            val url = data["url"]?.jsonPrimitive?.content
                ?: return@withContext BiliBiliQrGenerateResult.Error("Missing QR URL")
            val key = data["qrcode_key"]?.jsonPrimitive?.content
                ?: return@withContext BiliBiliQrGenerateResult.Error("Missing QR key")
            BiliBiliQrGenerateResult.Success(
                qrUrl = url,
                qrcodeKey = key,
                expiresAt = nowMillis() + QR_TTL_MS,
            )
        }
    }

    suspend fun poll(qrcodeKey: String): BiliBiliQrPollResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$POLL_URL?qrcode_key=$qrcodeKey")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .build()
        val response = client.newCall(request).execute()
        response.use { httpResponse ->
            val body = httpResponse.body?.string() ?: return@withContext BiliBiliQrPollResult.Error("Empty response")
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return@withContext BiliBiliQrPollResult.Error("Invalid JSON")
            val data = root["data"]?.jsonObject
                ?: return@withContext BiliBiliQrPollResult.Error("Missing data")
            val code = data["code"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: return@withContext BiliBiliQrPollResult.Error("Missing code")
            when (code) {
                0 -> {
                    val refreshToken = data["refresh_token"]?.jsonPrimitive?.contentOrNull
                        ?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_REFRESH_TOKEN_LENGTH }
                    val responseCookies = parseCookieHeaders(httpResponse.headers("Set-Cookie"))
                    val crossDomainCookies = if ("SESSDATA" in responseCookies) {
                        emptyMap()
                    } else {
                        data["url"]?.jsonPrimitive?.contentOrNull?.let(::fetchCrossDomainCookies).orEmpty()
                    }
                    val cookieHeader = (responseCookies + crossDomainCookies).toCookieHeader()
                    if ("SESSDATA=" in cookieHeader) {
                        BiliBiliQrPollResult.Confirmed(cookieHeader, refreshToken)
                    } else {
                        BiliBiliQrPollResult.Error("Missing SESSDATA in response")
                    }
                }
                else -> classifyBiliBiliQrCode(code)
            }
        }
    }

    private fun fetchCrossDomainCookies(rawUrl: String): Map<String, String> {
        val url = rawUrl.toHttpUrlOrNull() ?: return emptyMap()
        if (url.scheme != "https" || url.host != CROSS_DOMAIN_HOST ||
            url.encodedPath != CROSS_DOMAIN_PATH || url.username.isNotEmpty() || url.password.isNotEmpty()
        ) return emptyMap()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", WEB_USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .build()
        return client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
            .newCall(request)
            .execute()
            .use { response -> parseCookieHeaders(response.headers("Set-Cookie")) }
    }

    private fun parseCookieHeaders(headers: List<String>): Map<String, String> = headers.mapNotNull { header ->
        val pair = header.substringBefore(';').split('=', limit = 2)
        if (pair.size != 2 || pair[0] !in COOKIE_NAMES || pair[1].isEmpty()) null else pair[0] to pair[1]
    }.toMap()

    private fun Map<String, String>.toCookieHeader(): String = COOKIE_NAMES.mapNotNull { name ->
        this[name]?.let { "$name=$it" }
    }.joinToString("; ")

    companion object {
        private const val GENERATE_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        private const val POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
        private const val QR_TTL_MS = 180_000L
        private const val CROSS_DOMAIN_HOST = "passport.biligame.com"
        private const val CROSS_DOMAIN_PATH = "/x/passport-login/web/crossDomain"
        private const val MAX_REFRESH_TOKEN_LENGTH = 512
        private val COOKIE_NAMES = setOf("SESSDATA", "bili_jct", "buvid3", "DedeUserID", "DedeUserID__ckMd5", "sid")
        internal const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

internal fun classifyBiliBiliQrCode(code: Int): BiliBiliQrPollResult = when (code) {
    86038 -> BiliBiliQrPollResult.Expired
    86090 -> BiliBiliQrPollResult.Scanned
    // The current web endpoint uses 86101 while the QR is still waiting to be scanned.
    86001, 86101 -> BiliBiliQrPollResult.Waiting
    else -> BiliBiliQrPollResult.Error("Unexpected code: $code")
}

sealed class BiliBiliQrGenerateResult {
    data class Success(val qrUrl: String, val qrcodeKey: String, val expiresAt: Long) : BiliBiliQrGenerateResult()
    data class Error(val message: String) : BiliBiliQrGenerateResult()
}

sealed class BiliBiliQrPollResult {
    data class Confirmed(val cookieHeader: String, val refreshToken: String? = null) : BiliBiliQrPollResult()
    object Scanned : BiliBiliQrPollResult()
    object Waiting : BiliBiliQrPollResult()
    object Expired : BiliBiliQrPollResult()
    data class Error(val message: String) : BiliBiliQrPollResult()
}
