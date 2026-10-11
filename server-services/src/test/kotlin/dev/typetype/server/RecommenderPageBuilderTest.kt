package dev.typetype.server

import dev.typetype.server.models.RecommenderSettingsItem
import dev.typetype.server.services.RecommenderCallResult
import dev.typetype.server.services.RecommenderCandidateGroups
import dev.typetype.server.services.RecommenderClient
import dev.typetype.server.services.RecommenderPageBuilder
import dev.typetype.server.services.RecommenderPageRequest
import dev.typetype.server.services.RecommenderSnapshotService
import dev.typetype.server.services.RecommenderSourceFetcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommenderPageBuilderTest {
    private val client: RecommenderClient = mockk()
    private val snapshots: RecommenderSnapshotService = mockk()
    private val fetcher: RecommenderSourceFetcher = mockk()
    private val builder = RecommenderPageBuilder(client, snapshots, fetcher)
    private val videos = listOf(recommenderTestVideo("a"), recommenderTestVideo("b"), recommenderTestVideo("c"))
    private val snapshot = buildJsonObject { put("history_key", "k") }
    private val request = RecommenderPageRequest(
        userId = "u",
        algorithmId = "signals",
        settings = RecommenderSettingsItem(enabled = true),
        seed = 1,
        locale = "en",
        timezone = "UTC",
        timeoutMs = 500,
    )

    private fun plan(planId: String? = "p1"): RecommenderCallResult = RecommenderCallResult.Success(
        buildJsonObject {
            if (planId != null) put("plan_id", planId)
            put("sources", buildJsonArray { add(buildJsonObject { put("kind", "trending") }) })
        },
    )

    private fun ranked(vararg ids: String): RecommenderCallResult = RecommenderCallResult.Success(
        buildJsonObject {
            put("rows", buildJsonArray {
                add(buildJsonObject {
                    put("id", "row-1")
                    put("title", "A row")
                    put("reason", buildJsonObject { put("kind", "topic"); put("ref", "subject:a") })
                    put("videos", buildJsonArray {
                        ids.forEach { add(buildJsonObject { put("url", "https://www.youtube.com/watch?v=$it") }) }
                    })
                })
            })
        },
    )

    private fun ready() {
        coEvery { snapshots.snapshot(any(), any()) } returns snapshot
        coEvery { fetcher.fetch(any(), any(), any()) } returns
            RecommenderCandidateGroups(JsonArray(emptyList()), videos.associateBy { it.url })
    }

    @Test
    fun `a page keeps the rows, their reason and only videos Server fetched`() = runBlocking {
        ready()
        coEvery { client.plan(any(), any()) } returns plan()
        coEvery { client.rank(any(), any()) } returns ranked("b", "unknown", "a")
        val page = checkNotNull(builder.build(request))
        assertEquals(listOf("b", "a"), page.items.map { it.id })
        assertEquals("topic", page.rows.single().reason)
        assertEquals("subject:a", page.rows.single().reasonRef)
        assertEquals(2, page.rows.single().videoUrls.size)
    }

    @Test
    fun `the ranking names the plan instead of sending the snapshot again`() = runBlocking {
        ready()
        val body = slot<JsonObject>()
        coEvery { client.plan(any(), any()) } returns plan()
        coEvery { client.rank(capture(body), any()) } returns ranked("a")
        builder.build(request)
        assertEquals(setOf("plan_id", "candidates", "trace"), body.captured.keys)
    }

    @Test
    fun `a history the recommender no longer holds is sent again once`() = runBlocking {
        ready()
        coEvery { client.plan(any(), any()) } returnsMany listOf(RecommenderCallResult.Expired, plan())
        coEvery { client.rank(any(), any()) } returns ranked("a")
        assertEquals(listOf("a"), checkNotNull(builder.build(request)).items.map { it.id })
        coVerify(exactly = 1) { snapshots.snapshot(any(), resend = true) }
    }

    @Test
    fun `a plan let go meanwhile is ranked from the snapshot`() = runBlocking {
        ready()
        val bodies = mutableListOf<JsonObject>()
        coEvery { client.plan(any(), any()) } returns plan()
        coEvery { client.rank(capture(bodies), any()) } returnsMany listOf(RecommenderCallResult.Expired, ranked("c"))
        assertEquals(listOf("c"), checkNotNull(builder.build(request)).items.map { it.id })
        assertTrue("snapshot" in bodies.last())
    }

    @Test
    fun `a recommender that fails gives no page`() = runBlocking {
        ready()
        coEvery { client.plan(any(), any()) } returns RecommenderCallResult.Failure("HTTP 500")
        assertNull(builder.build(request))
        coEvery { client.plan(any(), any()) } returns plan()
        coEvery { client.rank(any(), any()) } returns RecommenderCallResult.Failure("timeout")
        assertNull(builder.build(request))
    }
}
