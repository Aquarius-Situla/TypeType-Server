package dev.typetype.server.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

interface DeArrowRemote {
    suspend fun branding(videoId: String): String?
    suspend fun thumbnail(videoId: String, timestamp: Double): ByteArray?
    suspend fun fallbackThumbnail(videoId: String): ByteArray?
}

class DeArrowClient(
    private val client: OkHttpClient = defaultClient(),
) : DeArrowRemote {
    private val limiter = Semaphore(MAX_CONCURRENT_REQUESTS)

    override suspend fun branding(videoId: String): String? = withContext(Dispatchers.IO) {
        limiter.withPermit {
            try {
                client.newCall(request("https://sponsor.ajay.app/api/branding?videoID=$videoId&fetchAll=true"))
                    .execute()
                    .use { response ->
                        when {
                            response.code == 404 -> null
                            !response.isSuccessful -> throw DeArrowUnavailableException()
                            else -> response.body.string().takeIf { it.isNotBlank() }
                        }
                    }
            } catch (exception: IOException) {
                throw DeArrowUnavailableException(exception)
            }
        }
    }

    override suspend fun thumbnail(videoId: String, timestamp: Double): ByteArray? =
        get("https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=$videoId&time=$timestamp")

    override suspend fun fallbackThumbnail(videoId: String): ByteArray? =
        get("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")

    private suspend fun get(url: String): ByteArray? = withContext(Dispatchers.IO) {
        limiter.withPermit {
            runCatching {
                client.newCall(
                    request(url),
                ).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body.bytes().takeIf { it.isNotEmpty() }
                }
            }.getOrNull()
        }
    }

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .get()
        .build()

    private companion object {
        private const val MAX_CONCURRENT_REQUESTS = 6
        private const val USER_AGENT = "TypeType-DeArrow/1.0"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
