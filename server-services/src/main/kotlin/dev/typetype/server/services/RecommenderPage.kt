package dev.typetype.server.services

import dev.typetype.server.models.HomeRecommendationRow
import dev.typetype.server.models.RecommenderPageTrace
import dev.typetype.server.models.VideoItem
import kotlinx.serialization.Serializable

@Serializable
data class RecommenderPage(
    val algorithm: String,
    val rows: List<HomeRecommendationRow>,
    val items: List<VideoItem>,
    val trace: RecommenderPageTrace? = null,
)
