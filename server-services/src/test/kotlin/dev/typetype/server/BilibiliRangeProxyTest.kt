package dev.typetype.server

import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.services.MAX_BILIBILI_SUBCHUNK_SIZE
import dev.typetype.server.services.OkHttpProxyService
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import okio.buffer
import java.io.IOException
import java.net.InetAddress

class BilibiliRangeProxyTest {

    @Test
    fun `BiliBili range proxy retries transport failures`() = runBlocking {
        var calls = 0
        val bytes = byteArrayOf(1, 2, 3, 4)
        val client = OkHttpClient.Builder()
            .dns(Dns { listOf(InetAddress.getByName("1.1.1.1")) })
            .addInterceptor { chain ->
                calls += 1
                val request = chain.request()
                assertEquals(OkHttpProxyService.BILIBILI_USER_AGENT, request.header("User-Agent"))
                assertEquals("https://www.bilibili.com", request.header("Referer"))
                assertEquals("*/*", request.header("Accept"))
                assertNull(request.header("Connection"))
                assertEquals("bytes=0-3", request.header("Range"))
                if (calls == 1) throw IOException("unexpected end of stream")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Range", "bytes 0-3/4")
                    .body(bytes.toResponseBody())
                    .build()
            }.build()

        val service = OkHttpProxyService(client)
        val result = service.pipe(
            url = "https://upos-hz-mirrorakam.akamaized.net/video.m4s",
            rangeHeader = "bytes=0-3",
            domandBid = null,
        )

        require(result is ExtractionResult.Success)
        assertEquals(2, calls)
        assertEquals(206, result.data.status)
        assertEquals("bytes 0-3/4", result.data.contentRange)
        result.data.stream.use { assertArrayEquals(bytes, it.readBytes()) }
    }

    @Test
    fun `BiliBili range proxy splits 2_5MB request into sub-1MB chunks upstream`() = runBlocking {
        // Simulating a 2.5MB segment (2,500,000 bytes)
        val totalRequested = 2_500_000L
        val fullData = ByteArray(totalRequested.toInt()) { (it % 256).toByte() }

        val requestedRanges = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .dns(Dns { listOf(InetAddress.getByName("1.1.1.1")) })
            .addInterceptor { chain ->
                val request = chain.request()
                val rangeHeader = request.header("Range") ?: ""
                requestedRanges.add(rangeHeader)

                // Parse the sub-range
                val match = Regex("""bytes=(\d+)-(\d+)""").find(rangeHeader)
                requireNotNull(match) { "Expected sub-range header, got: $rangeHeader" }
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].toInt()
                val subLen = end - start + 1

                // Strictly ensure every outgoing subchunk is <= 1MB (MAX_BILIBILI_SUBCHUNK_SIZE)
                assertTrue(subLen <= MAX_BILIBILI_SUBCHUNK_SIZE, "Subchunk of size $subLen exceeds 1MB limit!")

                val subBytes = fullData.copyOfRange(start, end + 1)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Range", "bytes $start-$end/10000000")
                    .header("Accept-Ranges", "bytes")
                    .body(subBytes.toResponseBody())
                    .build()
            }.build()

        val service = OkHttpProxyService(client)
        val result = service.pipe(
            url = "https://upos-sz-mirrorcosov.bilivideo.com/video.m4s",
            rangeHeader = "bytes=0-${totalRequested - 1}",
            domandBid = null,
        )

        require(result is ExtractionResult.Success)
        val proxyResponse = result.data
        assertEquals(206, proxyResponse.status)
        assertEquals(totalRequested, proxyResponse.contentLength)
        assertEquals("bytes 0-${totalRequested - 1}/10000000", proxyResponse.contentRange)

        // Read the entire stream and verify data integrity
        val receivedData = proxyResponse.stream.use { it.readBytes() }
        assertArrayEquals(fullData, receivedData)

        // Verify that 3 subchunks were requested, and every single one is under 1MB
        assertEquals(3, requestedRanges.size)
        assertEquals("bytes=0-999999", requestedRanges[0])
        assertEquals("bytes=1000000-1999999", requestedRanges[1])
        assertEquals("bytes=2000000-2499999", requestedRanges[2])
    }

    @Test
    fun `BiliBili subchunk proxy retries when a subsequent subchunk fails`() = runBlocking {
        val totalRequested = 2_200_000L
        val fullData = ByteArray(totalRequested.toInt()) { ((it * 3) % 256).toByte() }

        var chunk2Attempts = 0
        val client = OkHttpClient.Builder()
            .dns(Dns { listOf(InetAddress.getByName("1.1.1.1")) })
            .addInterceptor { chain ->
                val request = chain.request()
                val rangeHeader = request.header("Range") ?: ""
                val match = Regex("""bytes=(\d+)-(\d+)""").find(rangeHeader)!!
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].toInt()

                // Trigger network failure on first attempt of chunk 2
                if (start == 1_000_000) {
                    chunk2Attempts++
                    if (chunk2Attempts == 1) {
                        throw IOException("Connection reset on subchunk 2")
                    }
                }

                val subBytes = fullData.copyOfRange(start, end + 1)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Range", "bytes $start-$end/10000000")
                    .body(subBytes.toResponseBody())
                    .build()
            }.build()

        val service = OkHttpProxyService(client)
        val result = service.pipe(
            url = "https://upos-sz-mirrorcosov.bilivideo.com/video.m4s",
            rangeHeader = "bytes=0-${totalRequested - 1}",
            domandBid = null,
        )

        require(result is ExtractionResult.Success)
        val receivedData = result.data.stream.use { it.readBytes() }
        assertArrayEquals(fullData, receivedData)
        assertEquals(2, chunk2Attempts, "Subchunk 2 should have succeeded on retry")
    }

    @Test
    fun `BiliBili subchunk proxy does not fetch remaining subchunks if stream is closed early`() = runBlocking {
        val totalRequested = 3_000_000L
        val requestedRanges = mutableListOf<String>()

        val client = OkHttpClient.Builder()
            .dns(Dns { listOf(InetAddress.getByName("1.1.1.1")) })
            .addInterceptor { chain ->
                val request = chain.request()
                val rangeHeader = request.header("Range") ?: ""
                requestedRanges.add(rangeHeader)
                val dummy = ByteArray(1_000_000)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Range", "bytes 0-999999/10000000")
                    .body(dummy.toResponseBody())
                    .build()
            }.build()

        val service = OkHttpProxyService(client)
        val result = service.pipe(
            url = "https://upos-sz-mirrorcosov.bilivideo.com/video.m4s",
            rangeHeader = "bytes=0-${totalRequested - 1}",
            domandBid = null,
        )

        require(result is ExtractionResult.Success)
        // Read only 100 bytes then close immediately (simulating player seek / abort)
        result.data.stream.use { stream ->
            val buf = ByteArray(100)
            val count = stream.read(buf)
            assertEquals(100, count)
        }

        // Only the first subchunk should have been requested!
        assertEquals(1, requestedRanges.size)
        assertEquals("bytes=0-999999", requestedRanges[0])
    }

    @Test
    fun `BiliBili subchunk proxy rotates mirrors when initial subchunk fails on first mirror`() = runBlocking {
        val totalRequested = 2_200_000L
        val fullData = ByteArray(totalRequested.toInt()) { (it % 256).toByte() }

        val mirrorHostsCalled = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .dns(Dns { listOf(InetAddress.getByName("1.1.1.1")) })
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                mirrorHostsCalled.add(host)
                val rangeHeader = request.header("Range") ?: ""
                val match = Regex("""bytes=(\d+)-(\d+)""").find(rangeHeader)!!
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].toInt()

                // Primary mirror fails on initial subchunk
                if (host.contains("mirrorcosov")) {
                    throw IOException("Connection failed to mirrorcosov")
                }

                val subBytes = fullData.copyOfRange(start, end + 1)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Range", "bytes $start-$end/10000000")
                    .body(subBytes.toResponseBody())
                    .build()
            }.build()

        val service = OkHttpProxyService(client)
        val result = service.pipeProviderMediaWithAlternates(
            url = "https://upos-sz-mirrorcosov.bilivideo.com/video.m4s",
            rangeHeader = "bytes=0-${totalRequested - 1}",
            domandBid = null,
            alternateUrls = listOf("https://upos-sz-mirrorali.bilivideo.com/video.m4s"),
        )

        require(result is ExtractionResult.Success)
        val receivedData = result.data.stream.use { it.readBytes() }
        assertArrayEquals(fullData, receivedData)
        // First attempt was mirrorcosov (failed), then rotated to mirrorali (succeeded)
        assertEquals("upos-sz-mirrorcosov.bilivideo.com", mirrorHostsCalled[0])
        assertTrue(mirrorHostsCalled.any { it.contains("mirrorali") })
    }

    @Test
    fun `BiliBili subchunk proxy rotates mirrors when a subsequent subchunk fails`() = runBlocking {
        val totalRequested = 2_200_000L
        val fullData = ByteArray(totalRequested.toInt()) { ((it * 5) % 256).toByte() }

        val subchunk2Hosts = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .dns(Dns { listOf(InetAddress.getByName("1.1.1.1")) })
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val rangeHeader = request.header("Range") ?: ""
                val match = Regex("""bytes=(\d+)-(\d+)""").find(rangeHeader)!!
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].toInt()

                // Fail subchunk 2 on primary mirror
                if (start == 1_000_000) {
                    subchunk2Hosts.add(host)
                    if (host.contains("mirrorcosov")) {
                        throw IOException("mirrorcosov timed out on subchunk 2")
                    }
                }

                val subBytes = fullData.copyOfRange(start, end + 1)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Range", "bytes $start-$end/10000000")
                    .body(subBytes.toResponseBody())
                    .build()
            }.build()

        val service = OkHttpProxyService(client)
        val result = service.pipeProviderMediaWithAlternates(
            url = "https://upos-sz-mirrorcosov.bilivideo.com/video.m4s",
            rangeHeader = "bytes=0-${totalRequested - 1}",
            domandBid = null,
            alternateUrls = listOf("https://upos-sz-mirrorali.bilivideo.com/video.m4s"),
        )

        require(result is ExtractionResult.Success)
        val receivedData = result.data.stream.use { it.readBytes() }
        assertArrayEquals(fullData, receivedData)
        // Subchunk 2 should have attempted mirrorcosov first, then rotated to mirrorali
        assertEquals(listOf("upos-sz-mirrorcosov.bilivideo.com", "upos-sz-mirrorali.bilivideo.com"), subchunk2Hosts)
    }

    @Test
    fun `BiliBili subchunk stream handles zero byte read without advancing or closing stream`() {
        val totalRequested = 1_500_000L
        val fullData = ByteArray(totalRequested.toInt()) { (it % 256).toByte() }
        val subRanges = dev.typetype.server.services.BilibiliRangeSplitter.splitRange(0, totalRequested - 1, MAX_BILIBILI_SUBCHUNK_SIZE)

        val request = okhttp3.Request.Builder().url("https://upos-sz-mirrorcosov.bilivideo.com/video.m4s").build()
        val firstChunkBytes = fullData.copyOfRange(0, subRanges[0].end.toInt() + 1)
        val firstResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(206)
            .message("Partial Content")
            .body(firstChunkBytes.toResponseBody())
            .build()

        var zeroReturned = false
        val stream = dev.typetype.server.services.BilibiliSubchunkInputStream(
            execute = { req ->
                val rangeHeader = req.header("Range") ?: ""
                val match = Regex("""bytes=(\d+)-(\d+)""").find(rangeHeader)!!
                val s = match.groupValues[1].toInt()
                val e = match.groupValues[2].toInt()
                val slice = fullData.copyOfRange(s, e + 1)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .body(object : okhttp3.ResponseBody() {
                        override fun contentType() = null
                        override fun contentLength() = slice.size.toLong()
                        override fun source(): okio.BufferedSource {
                            val src = object : okio.Source {
                                var offset = 0
                                override fun timeout() = okio.Timeout.NONE
                                override fun close() {}
                                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                                    if (offset >= slice.size) return -1
                                    if (!zeroReturned) {
                                        zeroReturned = true
                                        return 0L // Yield zero bytes read!
                                    }
                                    val count = minOf(byteCount, (slice.size - offset).toLong()).toInt()
                                    sink.write(slice, offset, count)
                                    offset += count
                                    return count.toLong()
                                }
                            }
                            return src.buffer()
                        }
                    })
                    .build()
            },
            baseRequest = request,
            subRanges = subRanges,
            firstResponse = firstResponse,
            attemptsPerChunk = 3,
        )

        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val r = stream.read(buf)
            if (r == -1) break
            if (r > 0) out.write(buf, 0, r)
        }
        assertTrue(zeroReturned, "Zero-byte read should have occurred")
        assertArrayEquals(fullData, out.toByteArray())
    }
    @Test
    fun `BiliBili subchunk proxy excludes Akamai and prioritizes fast mirrors when blacklisted`() = runBlocking {
        System.setProperty("BILIBILI_BLACKLIST_AKAMAI_CDN", "true")
        try {
            val totalRequested = 2_000_000L
            val fullData = ByteArray(totalRequested.toInt()) { (it % 256).toByte() }
            val mirrorHostsCalled = mutableListOf<String>()

            val client = okhttp3.OkHttpClient.Builder()
                .dns(Dns { listOf(InetAddress.getByName("1.1.1.1")) })
                .addInterceptor { chain ->
                    val host = chain.request().url.host
                    mirrorHostsCalled.add(host)
                    val rangeHeader = chain.request().header("Range") ?: "bytes=0-999999"
                    val match = Regex("""bytes=(\d+)-(\d+)""").find(rangeHeader)!!
                    val s = match.groupValues[1].toInt()
                    val e = match.groupValues[2].toInt()
                    val slice = fullData.copyOfRange(s, e + 1)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes $s-$e/$totalRequested")
                        .body(slice.toResponseBody())
                        .build()
                }
                .build()

            val service = OkHttpProxyService(client)
            val result = service.pipeProviderMediaWithAlternates(
                url = "https://upos-hz-mirrorakam.akamaized.net/video.m4s",
                rangeHeader = "bytes=0-${totalRequested - 1}",
                domandBid = null,
                alternateUrls = listOf(
                    "https://upos-sz-mirrorcos.bilivideo.com/video.m4s",
                    "https://upos-sz-mirrorali.bilivideo.com/video.m4s",
                ),
            )

            assertTrue(result is dev.typetype.server.models.ExtractionResult.Success)
            val receivedData = (result as dev.typetype.server.models.ExtractionResult.Success).data.stream.use { it.readBytes() }
            assertArrayEquals(fullData, receivedData)

            // Akamai should NOT have been called at all
            assertFalse(mirrorHostsCalled.any { it.contains("akamaized") })
            // Even though mirrorcos was first in alternateUrls, mirrorali is prioritized higher
            assertEquals("upos-sz-mirrorali.bilivideo.com", mirrorHostsCalled[0])
        } finally {
            System.clearProperty("BILIBILI_BLACKLIST_AKAMAI_CDN")
        }
    }
}
