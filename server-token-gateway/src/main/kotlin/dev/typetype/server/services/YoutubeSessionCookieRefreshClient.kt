package dev.typetype.server.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal sealed interface YoutubeSessionRefreshResult {
    data class Refreshed(val cookies: String) : YoutubeSessionRefreshResult
    data object NotRequired : YoutubeSessionRefreshResult
    data object NeedsReconnect : YoutubeSessionRefreshResult
    data object RetryableFailure : YoutubeSessionRefreshResult
}

internal class YoutubeSessionCookieRefreshClient(
    client: OkHttpClient = OkHttpClient(),
) {
    private val client = client.newBuilder().followRedirects(false).build()

    suspend fun refresh(cookies: String): YoutubeSessionRefreshResult = withContext(Dispatchers.IO) {
        try {
            refreshBlocking(cookies)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            YoutubeSessionRefreshResult.RetryableFailure
        }
    }

    private fun refreshBlocking(cookies: String): YoutubeSessionRefreshResult {
        val request = Request.Builder()
            .url(SUBSCRIPTIONS_URL)
            .header("Cookie", cookies)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val location = response.header("Location").orEmpty()
            if (location.contains("accounts.google.com")) return YoutubeSessionRefreshResult.NeedsReconnect
            if (response.code == 401 || response.code == 403) return YoutubeSessionRefreshResult.NeedsReconnect
            if (!response.isSuccessful) return YoutubeSessionRefreshResult.RetryableFailure
            val merged = YoutubeSessionCookieJar.merge(cookies, response.headers.values("Set-Cookie"))
            if (merged == cookies) YoutubeSessionRefreshResult.NotRequired
            else YoutubeSessionRefreshResult.Refreshed(merged)
        }
    }

    private companion object {
        const val SUBSCRIPTIONS_URL = "https://www.youtube.com/feed/subscriptions"
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    }
}
