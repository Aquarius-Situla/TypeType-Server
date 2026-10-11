package dev.typetype.server.services

import dev.typetype.server.models.FavoriteItem
import dev.typetype.server.models.HistoryItem
import dev.typetype.server.models.RecommenderSettingsItem
import java.security.MessageDigest

private const val DAY_MS = 86_400_000L

class RecommenderDataFilter(
    private val settings: RecommenderSettingsItem,
    private val nowMs: Long,
    private val algorithmUses: Set<String>,
) {
    private val excluded = settings.excludedItems.toSet()

    fun uses(category: String): Boolean {
        if (category in settings.unusedData) return false
        val requiredAlgorithmData = when (category) {
            HISTORY, SHORTS_HISTORY -> "history"
            FAVORITES -> "favorites"
            SUBSCRIPTIONS -> "subscriptions"
            BLOCKED, NOT_INTERESTED -> "not_interested"
            else -> return false
        }
        return requiredAlgorithmData in algorithmUses
    }

    fun needsHistorySnapshot(): Boolean =
        uses(HISTORY) || uses(SHORTS_HISTORY) || uses(FAVORITES)

    fun history(items: List<HistoryItem>): List<HistoryItem> = items.filter { item ->
        val category = if (item.url.isShortsUrl()) SHORTS_HISTORY else HISTORY
        uses(category) && recent(category, item.watchedAt) &&
            historyRef(item) !in excluded && videoRef(item.url) !in excluded
    }

    fun favorites(items: List<FavoriteItem>): List<FavoriteItem> = items.filter { item ->
        uses(FAVORITES) && recent(FAVORITES, item.favoritedAt) && favoriteRef(item) !in excluded
    }

    fun recent(category: String, at: Long): Boolean {
        val days = settings.retentionDays[category]?.takeIf { it > 0 } ?: return true
        return at <= 0L || at >= nowMs - days * DAY_MS
    }

    fun digest(): String {
        val categories = listOf(HISTORY, SHORTS_HISTORY, FAVORITES).filter(::uses)
        if (categories.isEmpty()) return ""
        val hasHistory = HISTORY in categories || SHORTS_HISTORY in categories
        val relevantExclusions = excluded.filter { ref ->
            when {
                ref.startsWith("favorite:") -> FAVORITES in categories
                ref.startsWith("history:") || ref.startsWith("video:") -> hasHistory
                else -> false
            }
        }.sorted()
        val limits = settings.retentionDays
            .filter { (category, days) -> category in categories && days > 0 }
            .toSortedMap()
        if (relevantExclusions.isEmpty() && limits.isEmpty()) return ""
        val day = if (limits.isEmpty()) 0L else nowMs / DAY_MS
        val text = listOf(categories, relevantExclusions, limits, day).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(DIGEST_CHARS)
    }

    companion object {
        const val HISTORY = "history"
        const val SHORTS_HISTORY = "shorts_history"
        const val FAVORITES = "favorites"
        const val SUBSCRIPTIONS = "subscriptions"
        const val BLOCKED = "blocked"
        const val NOT_INTERESTED = "not_interested"
        private const val DIGEST_CHARS = 16

        fun historyRef(item: HistoryItem): String = "history:${item.id}"

        fun videoRef(url: String): String = "video:$url"

        fun favoriteRef(item: FavoriteItem): String = "favorite:${item.videoUrl}"
    }
}
