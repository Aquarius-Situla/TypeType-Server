package dev.typetype.server.models

import kotlinx.serialization.Serializable

@Serializable
data class HomeRecommendationsResponse(
    val items: List<VideoItem>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val debug: HomeRecommendationsDebug? = null,
    val algorithm: String? = null,
    val rows: List<HomeRecommendationRow>? = null,
    val preparing: Boolean? = null,
)
