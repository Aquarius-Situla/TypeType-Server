package dev.typetype.server.services

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.test.runTest

class BiliBiliCookieRefreshClientTest {
    @Test
    fun `rotation sends csrf fields and keeps only supported cookies`() = runTest {
        val requests = mutableListOf<Request>()
        val client = clientFor(requests) { request ->
            when {
                request.url.encodedPath.endsWith("/cookie/info") -> response(request, INFO_REFRESH)
                request.url.encodedPath.startsWith("/correspond/1/") -> response(
                    request,
                    "<div id=\"1-name\">refresh-csrf</div>",
                )
                request.url.encodedPath.endsWith("/cookie/refresh") -> response(
                    request,
                    """{"code":0,"data":{"refresh_token":"new-refresh-token"}}""",
                    "SESSDATA=new-session,1790000000; Path=/",
                    "bili_jct=new-csrf; Path=/",
                    "DedeUserID=123; Path=/",
                    "untrusted=value; Path=/",
                )
                else -> error("Unexpected request path")
            }
        }

        val result = BiliBiliCookieRefreshClient(client) { 1_000L }
            .inspectAndRotate("SESSDATA=old-session,1700000000; bili_jct=old-csrf; buvid3=device", "old-refresh-token")

        val rotated = assertInstanceOf(BiliBiliCookieRefreshResult.Rotated::class.java, result)
        assertEquals("new-refresh-token", rotated.refreshToken)
        assertEquals(1_790_000_000_000L, rotated.expiresAt)
        assertTrue(rotated.cookies.contains("SESSDATA=new-session,1790000000"))
        assertTrue(rotated.cookies.contains("bili_jct=new-csrf"))
        assertTrue(rotated.cookies.contains("buvid3=device"))
        assertTrue(rotated.cookies.contains("DedeUserID=123"))
        assertFalse(rotated.cookies.contains("untrusted"))

        val refreshRequest = requests.last()
        assertEquals("POST", refreshRequest.method)
        assertNull(refreshRequest.header("refresh_token"))
        assertEquals("old-session,1700000000", refreshRequest.header("Cookie")?.substringAfter("SESSDATA=")?.substringBefore(';'))
        val form = refreshRequest.body as FormBody
        assertEquals("old-csrf", form.value(0))
        assertEquals("refresh-csrf", form.value(1))
        assertEquals("main_web", form.value(2))
        assertEquals("old-refresh-token", form.value(3))
    }

    @Test
    fun `confirmation retry verifies already committed rotation`() = runTest {
        val requests = mutableListOf<Request>()
        val client = clientFor(requests) { request ->
            when {
                request.url.encodedPath.endsWith("/confirm/refresh") -> response(request, """{"code":-101}""")
                request.url.encodedPath.endsWith("/cookie/info") -> response(request, INFO_NOT_DUE)
                else -> error("Unexpected request path")
            }
        }

        val result = BiliBiliCookieRefreshClient(client)
            .confirmRotation("SESSDATA=new-session; bili_jct=new-csrf", "old-refresh-token")

        assertEquals(BiliBiliCookieRefreshConfirmation.Confirmed, result)
        assertEquals(listOf("/x/passport-login/web/confirm/refresh", "/x/passport-login/web/cookie/info"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `crypto helper produces a Correspond path and parses only the refresh csrf field`() {
        val path = BiliBiliCookieRefreshCrypto.correspondPath(1_790_000_000L)

        assertEquals(256, path.length)
        assertTrue(path.matches(Regex("[0-9a-f]{256}")))
        assertEquals("refresh-csrf", BiliBiliCookieRefreshCrypto.refreshCsrf(
            "<div id=\"other\">wrong</div><div id=\"1-name\">refresh-csrf</div>",
        ))
        assertNull(BiliBiliCookieRefreshCrypto.refreshCsrf("<div id=\"1-name\">not valid!</div>"))
    }

    private fun clientFor(requests: MutableList<Request>, respond: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            respond(request)
        }).build()

    private fun response(request: Request, body: String, vararg setCookies: String): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody())
        setCookies.forEach { builder.addHeader("Set-Cookie", it) }
        return builder.build()
    }

    private companion object {
        const val INFO_REFRESH = """{"code":0,"data":{"refresh":true,"timestamp":1790000000}}"""
        const val INFO_NOT_DUE = """{"code":0,"data":{"refresh":false,"timestamp":1790000000}}"""
    }
}
