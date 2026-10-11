package dev.typetype.server.services

import dev.typetype.server.models.RecommenderSettingsItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommenderDataFilterTest {
    @Test
    fun algorithm_use_is_an_allowlist_for_categories() {
        val filter = RecommenderDataFilter(RecommenderSettingsItem(), 0, setOf("subscriptions", "not_interested"))

        assertTrue(filter.uses(RecommenderDataFilter.SUBSCRIPTIONS))
        assertTrue(filter.uses(RecommenderDataFilter.BLOCKED))
        assertFalse(filter.uses(RecommenderDataFilter.HISTORY))
        assertFalse(filter.needsHistorySnapshot())
    }

    @Test
    fun digest_ignores_data_that_will_not_be_uploaded() {
        val uses = setOf("favorites")
        val baseline = RecommenderDataFilter(RecommenderSettingsItem(), 0, uses).digest()
        val historyChanged = RecommenderDataFilter(
            RecommenderSettingsItem(
                unusedData = listOf("history"),
                excludedItems = listOf("history:1", "video:https://www.youtube.com/watch?v=old"),
                retentionDays = mapOf("history" to 7),
            ),
            0,
            uses,
        ).digest()
        val favoriteExcluded = RecommenderDataFilter(
            RecommenderSettingsItem(excludedItems = listOf("favorite:https://www.youtube.com/watch?v=liked")),
            0,
            uses,
        ).digest()

        assertEquals(baseline, historyChanged)
        assertNotEquals(baseline, favoriteExcluded)
    }
}
