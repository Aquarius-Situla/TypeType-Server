package dev.typetype.server.services

import dev.typetype.server.models.RecommenderSettingsItem
import dev.typetype.server.models.SubscriptionItem

data class RecommenderSnapshotRequest(
    val algorithmId: String,
    val settings: RecommenderSettingsItem,
    val historyKey: String?,
    val subscriptions: List<SubscriptionItem>,
    val notInterestedUrls: List<String>,
    val mutedChannelUrls: List<String>,
    val nowMs: Long,
    val timezone: String,
    val locale: String,
    val seed: Int,
)
