package dev.typetype.server.services

import dev.typetype.server.models.RecommenderSettingsItem

data class RecommenderPageRequest(
    val userId: String,
    val algorithmId: String,
    val settings: RecommenderSettingsItem,
    val seed: Int,
    val locale: String,
    val timezone: String,
    val timeoutMs: Long,
)
