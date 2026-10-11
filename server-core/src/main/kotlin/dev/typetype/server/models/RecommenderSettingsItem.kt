package dev.typetype.server.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class RecommenderSettingsItem(
    val enabled: Boolean = false,
    val algorithmId: String? = null,
    val parameters: Map<String, JsonPrimitive> = emptyMap(),
    val mutedGroups: List<String> = emptyList(),
    val ignoredSignals: List<String> = emptyList(),
    val pinnedGroups: List<String> = emptyList(),
    val sessionResetAt: Long? = null,
    val customInterests: List<String> = emptyList(),
    val unusedData: List<String> = emptyList(),
    val excludedItems: List<String> = emptyList(),
    val retentionDays: Map<String, Int> = emptyMap(),
)
