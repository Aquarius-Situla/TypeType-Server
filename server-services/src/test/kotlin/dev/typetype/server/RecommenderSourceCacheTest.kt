package dev.typetype.server

import dev.typetype.server.services.RecommenderMetrics
import dev.typetype.server.services.RecommenderSourceCache
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecommenderSourceCacheTest {
    private val metrics = RecommenderMetrics()
    private val cache = RecommenderSourceCache(RecommenderTestCache(), metrics)

    @Test
    fun `a source is asked once, whoever asks next`() = runBlocking {
        var loads = 0
        repeat(3) {
            val videos = cache.getOrLoad("search:cats", ttlSeconds = 60) {
                loads++
                listOf(recommenderTestVideo("a"))
            }
            assertEquals(listOf("a"), videos.map { it.id })
        }
        assertEquals(1, loads)
        assertEquals(1, metrics.sourceRequests.get())
        assertEquals(2, metrics.sourceCacheHits.get())
    }

    @Test
    fun `an empty answer is not kept`() = runBlocking {
        var loads = 0
        repeat(2) {
            cache.getOrLoad("related:x", ttlSeconds = 60) {
                loads++
                emptyList()
            }
        }
        assertEquals(2, loads)
    }
}
