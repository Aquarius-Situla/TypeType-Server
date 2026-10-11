package dev.typetype.server

import dev.typetype.server.models.RecommenderSettingsItem
import dev.typetype.server.services.RecommenderSnapshotBuilder
import dev.typetype.server.services.RecommenderSnapshotRequest
import dev.typetype.server.services.toRecommenderCandidate
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommenderSnapshotBuilderTest {
    @Test
    fun `a snapshot names the history by its key and carries the person's corrections`() {
        val snapshot = RecommenderSnapshotBuilder.snapshot(
            RecommenderSnapshotRequest(
                algorithmId = "signals-mix",
                settings = RecommenderSettingsItem(
                    enabled = true,
                    parameters = mapOf("page_size" to JsonPrimitive(40)),
                    mutedGroups = listOf("g1"),
                    pinnedGroups = listOf("g2"),
                ),
                historyKey = "abc",
                subscriptions = emptyList(),
                notInterestedUrls = listOf("https://www.youtube.com/watch?v=no"),
                mutedChannelUrls = listOf("https://www.youtube.com/channel/muted"),
                nowMs = 1_000,
                timezone = "Europe/Paris",
                locale = "fr",
                seed = 7,
            ),
        )
        assertEquals("abc", snapshot["history_key"]?.jsonPrimitive?.content)
        assertFalse("history" in snapshot)
        assertEquals("signals-mix", snapshot["algorithm"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
        assertEquals("40", snapshot["algorithm"]?.jsonObject?.get("parameters")?.jsonObject?.get("page_size").toString())
        val overrides = checkNotNull(snapshot["overrides"]).jsonObject
        assertEquals(1, overrides["muted_channels"]?.jsonArray?.size)
        assertEquals(1, overrides["muted_groups"]?.jsonArray?.size)
        assertEquals(1, overrides["pinned_groups"]?.jsonArray?.size)
        assertEquals(1, snapshot["not_interested"]?.jsonArray?.size)
    }

    @Test
    fun `a candidate carries no length Server does not have, and a Short by its address`() {
        val long = recommenderTestVideo("a").toRecommenderCandidate()
        assertFalse("duration_s" in long)
        assertFalse(checkNotNull(long["is_short"]).jsonPrimitive.boolean)
        val short = recommenderTestVideo("b").copy(url = "https://www.youtube.com/shorts/b").toRecommenderCandidate()
        assertTrue(checkNotNull(short["is_short"]).jsonPrimitive.boolean)
    }
}
