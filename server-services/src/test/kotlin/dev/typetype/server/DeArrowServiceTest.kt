package dev.typetype.server

import dev.typetype.server.services.DeArrowUnavailableException
import dev.typetype.server.services.DeArrowRemote
import dev.typetype.server.services.DeArrowService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeArrowServiceTest {
    @Test
    fun `selects accepted title and thumbnail and caches branding`() = runBlocking {
        val remote = FakeDeArrowRemote()
        val service = DeArrowService(FakeCacheService(), remote)
        val first = service.get("stZ3ZoR_8eg")
        val second = service.get("stZ3ZoR_8eg")
        assertEquals("Clear title", first?.title)
        assertEquals("/dearrow/thumbnail?videoId=stZ3ZoR_8eg&time=12.5", first?.thumbnailUrl)
        assertEquals(listOf("Rejected title", "Clear title", "Original title"), first?.titles?.map { it.title })
        assertEquals(listOf(-2, 2, 0), first?.titles?.map { it.votes })
        assertEquals(listOf(8.0, 12.5, null), first?.thumbnails?.map { it.timestamp })
        assertEquals(0.4, first?.randomTime)
        assertEquals(100.0, first?.videoDuration)
        assertEquals(first, second)
        assertEquals(1, remote.brandingCalls)
    }

    @Test
    fun `rejects invalid video id without remote call`() = runBlocking {
        val remote = FakeDeArrowRemote()
        val service = DeArrowService(FakeCacheService(), remote)
        assertNull(service.get("invalid"))
        assertEquals(0, remote.brandingCalls)
    }

    @Test
    fun `does not cache upstream failures`() = runBlocking {
        val cache = FakeCacheService()
        val remote = FakeDeArrowRemote().apply { brandingResult = null }
        val service = DeArrowService(cache, remote)

        assertThrows(DeArrowUnavailableException::class.java) {
            runBlocking { service.get("stZ3ZoR_8eg") }
        }
        assertTrue(cache.keys().isEmpty())

        remote.brandingResult = FakeDeArrowRemote.BRANDING
        assertEquals("Clear title", service.get("stZ3ZoR_8eg")?.title)
        assertEquals(2, remote.brandingCalls)
    }

    @Test
    fun `falls back to the original thumbnail when generation fails`() = runBlocking {
        val cache = FakeCacheService()
        val remote = FakeDeArrowRemote().apply { thumbnailResult = null }
        val service = DeArrowService(cache, remote)

        val first = service.thumbnail("stZ3ZoR_8eg", 12.5)
        val second = service.thumbnail("stZ3ZoR_8eg", 12.5)
        assertArrayEquals(FakeDeArrowRemote.FALLBACK_THUMBNAIL, first?.bytes)
        assertEquals(true, first?.fallback)
        assertArrayEquals(FakeDeArrowRemote.FALLBACK_THUMBNAIL, second?.bytes)
        assertEquals(true, second?.fallback)
        assertEquals(1, remote.thumbnailCalls)
        assertEquals(1, remote.fallbackThumbnailCalls)
    }
}

private class FakeDeArrowRemote : DeArrowRemote {
    var brandingCalls = 0
    var thumbnailCalls = 0
    var fallbackThumbnailCalls = 0
    var brandingResult: String? = BRANDING
    var thumbnailResult: ByteArray? = byteArrayOf(1, 2, 3)

    override suspend fun branding(videoId: String): String? {
        brandingCalls += 1
        return brandingResult
    }

    override suspend fun thumbnail(videoId: String, timestamp: Double): ByteArray? {
        thumbnailCalls += 1
        return thumbnailResult
    }

    override suspend fun fallbackThumbnail(videoId: String): ByteArray {
        fallbackThumbnailCalls += 1
        return FALLBACK_THUMBNAIL
    }

    companion object {
        const val BRANDING = """{"titles":[{"title":"Rejected title","votes":-2,"locked":false,"original":false,"UUID":"rejected"},{"title":"Clear title","votes":2,"locked":false,"original":false,"UUID":"accepted"},{"title":"Original title","votes":0,"locked":false,"original":true,"UUID":"original"}],"thumbnails":[{"timestamp":8.0,"votes":-1,"locked":false,"original":false,"UUID":"rejected-thumb"},{"timestamp":12.5,"votes":1,"locked":false,"original":false,"UUID":"accepted-thumb"},{"votes":0,"locked":false,"original":true,"UUID":"original-thumb"}],"videoDuration":100,"randomTime":0.4}"""
        val FALLBACK_THUMBNAIL = byteArrayOf(9, 8, 7)
    }
}
