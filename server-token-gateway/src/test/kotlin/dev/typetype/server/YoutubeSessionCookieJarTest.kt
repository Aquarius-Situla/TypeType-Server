package dev.typetype.server

import dev.typetype.server.services.YoutubeSessionCookieJar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class YoutubeSessionCookieJarTest {
    @Test
    fun `updates rotated cookies and keeps the rest`() {
        val merged = YoutubeSessionCookieJar.merge(
            "SID=old-sid; SAPISID=old-api; HSID=keep",
            listOf("SID=new-sid; Path=/; Domain=.youtube.com; HttpOnly", "SIDCC=fresh; Path=/"),
        )

        assertEquals("SID=new-sid; SAPISID=old-api; HSID=keep; SIDCC=fresh", merged)
    }

    @Test
    fun `ignores cookies outside the allowed set`() {
        val merged = YoutubeSessionCookieJar.merge(
            "SID=sid",
            listOf("TRACKER=value; Path=/", "__Secure-1PSIDTS=rotated; Path=/"),
        )

        assertEquals("SID=sid; __Secure-1PSIDTS=rotated", merged)
    }

    @Test
    fun `removes cookies cleared by the server`() {
        val merged = YoutubeSessionCookieJar.merge(
            "SID=sid; SIDCC=old",
            listOf("SIDCC=; Max-Age=0; Path=/"),
        )

        assertEquals("SID=sid", merged)
    }
}
