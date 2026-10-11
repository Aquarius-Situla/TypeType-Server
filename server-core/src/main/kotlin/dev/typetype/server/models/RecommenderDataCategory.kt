package dev.typetype.server.models

import kotlinx.serialization.Serializable

@Serializable
data class RecommenderDataCategory(
    val id: String,
    val count: Int,
    val oldestAt: Long? = null,
    val use: Boolean = true,
    val retentionDays: Int? = null,
    val collect: Boolean? = null,
    val items: List<RecommenderDataItem> = emptyList(),
)

@Serializable
data class RecommenderDataItem(
    val id: String,
    val title: String,
    val channel: String? = null,
    val at: Long = 0L,
    val watchedPercent: Int? = null,
    val excluded: Boolean = false,
    val thumbnailUrl: String? = null,
    val viewings: Int = 1,
)
