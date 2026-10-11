package dev.typetype.server.models

import kotlinx.serialization.Serializable

@Serializable
data class HomeRecommendationRow(
    val id: String,
    val title: String,
    val reason: String,
    val reasonRef: String? = null,
    val videoUrls: List<String>,
)
