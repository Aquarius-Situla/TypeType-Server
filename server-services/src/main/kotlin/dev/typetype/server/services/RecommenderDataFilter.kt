package dev.typetype.server.services

import dev.typetype.server.models.FavoriteItem
import dev.typetype.server.models.HistoryItem
import dev.typetype.server.models.RecommenderSettingsItem
import java.security.MessageDigest

private const val DAY_MS = 86_400_000L

class RecommenderDataFilter(private val settings: RecommenderSettingsItem, private val nowMs: Long) {
    private val excluded = settings.excludedItems.toSet()

    fun uses(category: String): Boolean = category !in settings.unusedData

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
        val limits = settings.retentionDays.filterValues { it > 0 }.toSortedMap()
        if (settings.unusedData.isEmpty() && excluded.isEmpty() && limits.isEmpty()) return ""
        val day = if (limits.isEmpty()) 0L else nowMs / DAY_MS
        val text = listOf(settings.unusedData.sorted(), excluded.sorted(), limits, day).joinToString("|")
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
