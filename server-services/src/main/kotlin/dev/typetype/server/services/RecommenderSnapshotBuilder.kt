package dev.typetype.server.services

import dev.typetype.server.models.FavoriteItem
import dev.typetype.server.models.HistoryItem
import dev.typetype.server.models.RecommenderSettingsItem
import dev.typetype.server.models.SubscriptionItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MILLIS_PER_SECOND = 1_000L

object RecommenderSnapshotBuilder {
    fun historyUpload(
        key: String,
        history: List<HistoryItem>,
        positionsMs: Map<String, Long>,
        favorites: List<FavoriteItem>,
        part: Int = 0,
        last: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("key", key)
        put("part", part)
        put("last", last)
        put("history", buildJsonArray { history.forEach { add(it.toSnapshot(positionsMs[it.url])) } })
        put("favorites", buildJsonArray { favorites.forEach { add(it.toSnapshot()) } })
    }

    fun snapshot(request: RecommenderSnapshotRequest): JsonObject = buildJsonObject {
        put("algorithm", buildJsonObject {
            put("id", request.algorithmId)
            put("parameters", JsonObject(request.settings.parameters))
        })
        put("context", buildJsonObject {
            put("now", request.nowMs)
            put("timezone", request.timezone)
            put("locale", request.locale)
            put("seed", request.seed)
        })
        put("history_key", request.historyKey)
        put("subscriptions", buildJsonArray { request.subscriptions.forEach { add(it.toSnapshot()) } })
        put("not_interested", buildJsonArray { request.notInterestedUrls.forEach { add(videoRef("not-interested", it)) } })
        put("overrides", buildJsonObject {
            put("muted_channels", request.mutedChannelUrls.toJsonArray())
            put("muted_groups", request.settings.mutedGroups.toJsonArray())
            put("ignored_signals", request.settings.ignoredSignals.toJsonArray())
            put("pinned_groups", request.settings.pinnedGroups.toJsonArray())
            put("custom_interests", request.settings.customInterests.toJsonArray())
            request.settings.sessionResetAt?.let { put("session_reset_at", it) }
        })
    }

    private fun HistoryItem.toSnapshot(positionMs: Long?): JsonObject = buildJsonObject {
        put("id", "history:$id")
        put("video_url", url)
        put("channel_url", channelUrl)
        put("channel_name", channelName)
        put("title", title)
        if (duration > 0L) {
            put("duration_s", duration)
            put("progress_s", maxOf(progress, (positionMs ?: 0L) / MILLIS_PER_SECOND))
        }
        put("watched_at", watchedAt)
        put("is_short", url.isShortsUrl())
    }

    private fun FavoriteItem.toSnapshot(): JsonObject = buildJsonObject {
        put("id", "favorite:$videoUrl")
        put("video_url", videoUrl)
        put("channel_url", channelUrl)
        put("title", title)
        if (favoritedAt > 0L) put("added_at", favoritedAt)
        put("is_short", videoUrl.isShortsUrl())
    }

    private fun SubscriptionItem.toSnapshot(): JsonObject = buildJsonObject {
        put("id", "subscription:$channelUrl")
        put("channel_url", channelUrl)
        put("name", name)
    }

    private fun videoRef(kind: String, url: String): JsonObject = buildJsonObject {
        put("id", "$kind:$url")
        put("video_url", url)
    }

    private fun List<String>.toJsonArray(): JsonArray = JsonArray(map(::JsonPrimitive))
}
