package dev.typetype.server.services

import dev.typetype.server.models.VideoItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A video as the recommender reads it (its contract's `Candidate`). No length is ever looked up,
 * and telling a Short by its length is the recommender's own rule: only what the provider
 * says is passed on.
 */
fun VideoItem.toRecommenderCandidate(): JsonObject = buildJsonObject {
    put("url", url)
    put("title", title)
    put("uploader_url", uploaderUrl)
    put("uploader_name", uploaderName)
    if (duration > 0L) put("duration_s", duration)
    if (viewCount >= 0L) put("view_count", viewCount)
    val uploadedAt = publishedAt?.takeIf { it > 0L } ?: uploaded.takeIf { it > 0L }
    if (uploadedAt != null) put("uploaded_at", uploadedAt)
    put("is_short", isShortFormContent || url.isShortsUrl())
}

fun String.isShortsUrl(): Boolean = contains("/shorts/")

fun VideoItem.startsLater(nowMs: Long): Boolean =
    (publishedAt?.takeIf { it > 0L } ?: uploaded.takeIf { it > 0L })?.let { it > nowMs } ?: false
