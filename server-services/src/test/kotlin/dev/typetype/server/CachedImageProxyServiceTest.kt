package dev.typetype.server

import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.models.ProxyResponse
import dev.typetype.server.services.CachedImageProxyService
import dev.typetype.server.services.ProviderMediaAwareProxyService
import dev.typetype.server.services.ProxyService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class CachedImageProxyServiceTest {
    private class Provider(private val cacheControl: String?, private val body: ByteArray = byteArrayOf(1, 2, 3)) :
        ProxyService, ProviderMediaAwareProxyService {
        var asked = 0

        override suspend fun pipe(url: String, rangeHeader: String?, domandBid: String?): ExtractionResult<ProxyResponse> {
            asked += 1
            return ExtractionResult.Success(
                ProxyResponse(200, "image/jpeg", body.size.toLong(), null, null, ByteArrayInputStream(body), {}, cacheControl),
            )
        }

        override suspend fun pipeProviderMedia(url: String, rangeHeader: String?, domandBid: String?) =
            pipe(url, rangeHeader, domandBid)
    }

    private suspend fun CachedImageProxyService<Provider>.image(url: String, range: String? = null): ProxyResponse =
        (pipe(url, range) as ExtractionResult.Success).data

    @Test
    fun `an image is asked to the provider once and served whole afterwards`() = runTest {
        val provider = Provider("public, max-age=300")
        val service = CachedImageProxyService(provider, FakeCacheService())
        val first = service.image("https://i.ytimg.com/vi/abc/mqdefault.jpg")
        val second = service.image("https://i.ytimg.com/vi/abc/mqdefault.jpg")
        assertEquals(1, provider.asked)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.stream.readAllBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), second.stream.readAllBytes())
        assertEquals("image/jpeg", second.contentType)
        assertEquals("public, max-age=86400", second.cacheControl)
    }

    @Test
    fun `a placeholder for a missing thumbnail is not kept`() = runTest {
        val provider = Provider("public, max-age=30")
        val service = CachedImageProxyService(provider, FakeCacheService())
        repeat(2) { service.image("https://i.ytimg.com/vi/abc/mqdefault.jpg") }
        assertEquals(2, provider.asked)
    }

    @Test
    fun `media, ranges and other hosts go straight to the provider`() = runTest {
        val provider = Provider("public, max-age=300")
        val service = CachedImageProxyService(provider, FakeCacheService())
        repeat(2) { service.image("https://rr1.googlevideo.com/videoplayback?id=1") }
        repeat(2) { service.image("https://i.ytimg.com/vi/abc/mqdefault.jpg", range = "bytes=0-1") }
        assertEquals(4, provider.asked)
    }

    @Test
    fun `an image too large to keep is still handed on whole`() = runTest {
        val large = ByteArray(600 * 1024) { 7 }
        val provider = Provider("public, max-age=300", large)
        val service = CachedImageProxyService(provider, FakeCacheService())
        assertEquals(large.size, service.image("https://yt3.ggpht.com/big").stream.readAllBytes().size)
        service.image("https://yt3.ggpht.com/big")
        assertEquals(2, provider.asked)
    }
}
