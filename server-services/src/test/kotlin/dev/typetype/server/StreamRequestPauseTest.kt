package dev.typetype.server

import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.models.StreamResponse
import dev.typetype.server.services.CachedStreamService
import dev.typetype.server.services.StreamRequestWatch
import dev.typetype.server.services.StreamService
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StreamRequestPauseTest {
    private class Provider : StreamService {
        var asked = 0

        override suspend fun getStreamInfo(url: String): ExtractionResult<StreamResponse> {
            asked += 1
            return ExtractionResult.Failure("no such video")
        }
    }

    private class Budget(var places: Int, var paused: Boolean = false) : StreamRequestWatch.Listener {
        var counted = 0

        override suspend fun opened(alreadyCounted: Boolean, blocked: Boolean) {
            if (!alreadyCounted) counted += 1
        }

        override suspend fun paused(): Boolean = paused

        override suspend fun admit(): Boolean {
            if (paused || places <= 0) return false
            places -= 1
            return true
        }
    }

    private fun service(provider: Provider, budget: Budget) =
        CachedStreamService(provider, FakeCacheService(), watch = StreamRequestWatch().also { it.listener = budget })

    private suspend fun CachedStreamService.background(url: String) =
        withContext(StreamRequestWatch.NobodyWaiting.mark) { getStreamInfo(url) } as ExtractionResult.Failure

    @Test
    fun `a video nobody is waiting for is not asked while the provider refuses`() = runTest {
        val provider = Provider()
        val budget = Budget(places = 10, paused = true)
        val service = service(provider, budget)
        assertEquals(CachedStreamService.PACED_CODE, service.background("https://youtu.be/a").code)
        assertEquals(0, provider.asked)
        service.getStreamInfo("https://youtu.be/a")
        assertEquals(1, provider.asked)
        assertEquals(1, budget.counted)
        budget.paused = false
        service.background("https://youtu.be/b")
        assertEquals(2, provider.asked)
    }

    @Test
    fun `videos nobody is waiting for stop at the limit of the minute`() = runTest {
        val provider = Provider()
        val budget = Budget(places = 2)
        val service = service(provider, budget)
        repeat(5) { service.background("https://youtu.be/v$it") }
        assertEquals(2, provider.asked)
        assertEquals(0, budget.counted)
        service.getStreamInfo("https://youtu.be/play")
        assertEquals(3, provider.asked)
    }

    @Test
    fun `a caller that counted its own request only stops for the pause`() = runTest {
        val provider = Provider()
        val budget = Budget(places = 0)
        val service = service(provider, budget)
        val own = StreamRequestWatch.NobodyWaiting.mark + StreamRequestWatch.AlreadyCounted.mark
        withContext(own) { service.getStreamInfo("https://youtu.be/a") }
        assertEquals(1, provider.asked)
        budget.paused = true
        withContext(own) { service.getStreamInfo("https://youtu.be/b") }
        assertEquals(1, provider.asked)
    }
}
