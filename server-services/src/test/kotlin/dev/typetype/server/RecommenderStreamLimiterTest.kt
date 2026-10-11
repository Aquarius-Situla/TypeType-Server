package dev.typetype.server

import dev.typetype.server.services.RecommenderStreamLimiter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommenderStreamLimiterTest {
    private var now = 0L
    private val limiter = RecommenderStreamLimiter(RecommenderTestCache(), limitPerMinute = { 2 }, now = { now })

    @Test
    fun `the limit holds for the minute and for everyone`() = runBlocking {
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        assertEquals(3, limiter.usedThisMinute())
    }

    @Test
    fun `a new minute starts a new count`() = runBlocking {
        repeat(3) { limiter.tryAcquire() }
        now = 60_000
        assertEquals(0, limiter.usedThisMinute())
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `a video opened by anyone else takes a place too`() = runBlocking {
        limiter.opened(alreadyCounted = false, blocked = false)
        limiter.opened(alreadyCounted = false, blocked = false)
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `the recommender's own request is not counted twice`() = runBlocking {
        assertTrue(limiter.tryAcquire())
        limiter.opened(alreadyCounted = true, blocked = false)
        assertEquals(1, limiter.usedThisMinute())
    }

    @Test
    fun `after a refusal by YouTube the recommender asks nothing for a while`() = runBlocking {
        limiter.opened(alreadyCounted = false, blocked = true)
        assertTrue(limiter.walled())
        now = 120_000
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `the pause ends by itself once its time is up`() = runBlocking {
        val cache = ExpiringCache { now }
        val paced = RecommenderStreamLimiter(cache, limitPerMinute = { 2 }, wallCooldownSeconds = 900, now = { now })
        paced.opened(alreadyCounted = false, blocked = true)
        assertTrue(paced.paused())
        assertFalse(paced.admit())
        now = 899_000
        assertTrue(paced.paused())
        now = 901_000
        assertFalse(paced.paused())
        assertTrue(paced.admit())
    }

    @Test
    fun `a video that opens normally starts no pause`() = runBlocking {
        repeat(5) { limiter.opened(alreadyCounted = false, blocked = false) }
        assertFalse(limiter.paused())
    }

    private class ExpiringCache(private val now: () -> Long) : dev.typetype.server.cache.CacheService {
        private val kept = HashMap<String, Pair<String, Long>>()

        override suspend fun get(key: String): String? = kept[key]?.takeIf { it.second > now() }?.first

        override suspend fun set(key: String, value: String, ttlSeconds: Long) {
            kept[key] = value to now() + ttlSeconds * 1_000
        }

        override suspend fun increment(key: String, ttlSeconds: Long): Long {
            val next = (get(key)?.toLongOrNull() ?: 0L) + 1
            set(key, next.toString(), ttlSeconds)
            return next
        }

        override suspend fun delete(key: String) {
            kept.remove(key)
        }
    }

    @Test
    fun `a limit of zero refuses everything`() = runBlocking {
        val closed = RecommenderStreamLimiter(RecommenderTestCache(), limitPerMinute = { 0 })
        assertFalse(closed.tryAcquire())
    }
}
