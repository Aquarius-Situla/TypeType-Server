package dev.typetype.server.models

import kotlinx.serialization.Serializable

@Serializable
data class RecommenderBackup(
    val settings: RecommenderSettingsItem? = null,
    val feedback: List<RecommenderFeedbackItem> = emptyList(),
)
