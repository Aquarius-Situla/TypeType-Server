package dev.typetype.server

import dev.typetype.server.models.VideoItem
import dev.typetype.server.services.RecommenderSourceFetcher
import dev.typetype.server.services.RecommenderSourceLoader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommenderSourceFetcherTest {
    private val loader: RecommenderSourceLoader = mockk()
    private val feed = listOf(recommenderTestVideo("f1"), recommenderTestVideo("f2"), recommenderTestVideo("f3"))
    private val fetcher = RecommenderSourceFetcher(
        loader = loader,
        subscriptionFeed = { feed },
        knownVideos = { _, urls -> urls.map { recommenderTestVideo(it.substringAfter("v=")) } },
        config = recommenderTestConfig(),
    )

    private fun source(vararg fields: Pair<String, Any>): JsonObject = JsonObject(
        fields.associate { (name, value) ->
            name to when (value) {
                is Int -> JsonPrimitive(value)
                is List<*> -> JsonArray(value.map { JsonPrimitive(it.toString()) })
                else -> JsonPrimitive(value.toString())
            }
        },
    )

    private fun videos(groups: JsonArray, index: Int): List<String> =
        groups[index].jsonObject["videos"]?.jsonArray.orEmpty().map { it.jsonObject["url"].toString() }

    private fun related(id: String): List<VideoItem> = listOf(recommenderTestVideo("$id-r1"), recommenderTestVideo("$id-r2"))

    @Test
    fun `each source is answered in the order of the plan, with its limit`() = runBlocking {
        coEvery { loader.search("cats") } returns (1..5).map { recommenderTestVideo("s$it") }
        coEvery { loader.related(any()) } returns related("x")
        val plan = JsonArray(
            listOf(
                source("kind" to "search", "query" to "cats", "limit" to 2),
                source("kind" to "related", "video_url" to "https://www.youtube.com/watch?v=x"),
                source("kind" to "videos", "urls" to listOf("https://www.youtube.com/watch?v=k1")),
            ),
        )
        val fetched = fetcher.fetch("u", plan, seed = 1)
        assertEquals(3, fetched.groups.size)
        assertEquals(2, videos(fetched.groups, 0).size)
        assertEquals(2, videos(fetched.groups, 1).size)
        assertEquals(1, videos(fetched.groups, 2).size)
        assertEquals(plan[0], fetched.groups[0].jsonObject["source"])
        assertEquals(5, fetched.videosByUrl.size)
    }

    @Test
    fun `a source that fails or is unknown is left empty and the page goes on`() = runBlocking {
        coEvery { loader.search(any()) } throws IllegalStateException("provider down")
        coEvery { loader.trending() } returns listOf(recommenderTestVideo("t1"))
        val plan = JsonArray(
            listOf(source("kind" to "search", "query" to "q"), source("kind" to "holograms"), source("kind" to "trending")),
        )
        val fetched = fetcher.fetch("u", plan, seed = 1)
        assertTrue(videos(fetched.groups, 0).isEmpty())
        assertTrue(videos(fetched.groups, 1).isEmpty())
        assertEquals(1, videos(fetched.groups, 2).size)
    }

    @Test
    fun `related videos of the subscription feed start from its newest videos`() = runBlocking {
        coEvery { loader.related(any()) } answers { related(firstArg<String>().substringAfter("v=")) }
        val plan = JsonArray(
            listOf(
                source("kind" to "subscription_feed"),
                source("kind" to "related", "of" to "subscription_feed", "count" to 2, "limit" to 1),
            ),
        )
        val fetched = fetcher.fetch("u", plan, seed = 1)
        assertEquals(3, videos(fetched.groups, 0).size)
        assertEquals(2, videos(fetched.groups, 1).size)
        coVerify(exactly = 1) { loader.related(feed[0].url) }
        coVerify(exactly = 1) { loader.related(feed[1].url) }
        coVerify(exactly = 0) { loader.related(feed[2].url) }
    }

    @Test
    fun `a live event announced for later is not a candidate`() = runBlocking {
        val later = recommenderTestVideo("later").copy(publishedAt = System.currentTimeMillis() + 3_600_000)
        coEvery { loader.trending() } returns listOf(recommenderTestVideo("now"), later)
        val fetched = fetcher.fetch("u", JsonArray(listOf(source("kind" to "trending"))), seed = 1)
        assertEquals(1, videos(fetched.groups, 0).size)
        assertTrue("https://www.youtube.com/watch?v=later" !in fetched.videosByUrl)
    }
}
