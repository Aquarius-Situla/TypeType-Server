package dev.typetype.server.downloader

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

internal object ExtractorRequestLog : Interceptor {
    val enabled: Boolean get() = System.getenv("YOUTUBE_REQUEST_LOG") == "1"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val target = "${url.host}${url.encodedPath}"
        val response = chain.proceed(request)
        println("YT_OUT ${request.method} $target status=${response.code}")
        if (response.code == 200 && url.encodedPath.endsWith("/player")) println(playability(request, response))
        if (response.code == 403 || response.code == 429) println("YT_OUT_REFUSED $target status=${response.code} by ${callers()}")
        return response
    }

    private fun callers(): String = Throwable().stackTrace
        .filter { it.className.startsWith("org.schabi") || it.className.startsWith("dev.typetype") }
        .drop(1)
        .take(8)
        .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    /** A refusal to play a video is written in an answer that is otherwise "200", and nowhere else. */
    private fun playability(request: Request, response: Response): String {
        val asked = json(Buffer().also { request.body?.writeTo(it) }.readUtf8())
        val answer = json(response.peekBody(PEEK_BYTES).string())
        val client = asked?.getObject("context")?.getObject("client")?.getString("clientName") ?: "?"
        val status = answer?.getObject("playabilityStatus")
        val formats = answer?.has("streamingData") == true
        return "YT_PLAYER client=$client playability=${status?.getString("status") ?: "?"} formats=$formats " +
            "reason=\"${status?.getString("reason")?.take(90) ?: "-"}\""
    }

    private fun json(text: String): JsonObject? = try {
        JsonParser.`object`().from(text)
    } catch (_: JsonParserException) {
        null
    }

    private const val PEEK_BYTES = 4L * 1024 * 1024
}
