package dev.typetype.server.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class RecommenderFeedbackItem(
    val id: String = "",
    val kind: String,
    val verdict: String,
    val comment: String? = null,
    val videoUrl: String? = null,
    val reason: String? = null,
    val title: String? = null,
    val channelName: String? = null,
    val thumbnailUrl: String? = null,
    val algorithmId: String = "",
    val parameters: Map<String, JsonPrimitive> = emptyMap(),
    val createdAt: Long = 0L,
)
