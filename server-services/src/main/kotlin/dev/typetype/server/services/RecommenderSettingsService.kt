package dev.typetype.server.services

import dev.typetype.server.cache.CacheJson
import dev.typetype.server.cache.CacheService
import dev.typetype.server.db.DatabaseFactory
import dev.typetype.server.models.RecommenderSettingsItem

class RecommenderSettingsService(
    private val cache: CacheService,
    private val config: RecommenderConfig,
    private val onByDefault: suspend () -> Boolean = { false },
) {
    suspend fun get(userId: String): RecommenderSettingsItem {
        cached(userId)?.let { return it }
        val settings = DatabaseFactory.query { RecommenderUserDataRepository.settings(userId) }
            ?: return RecommenderSettingsItem(enabled = onByDefault())
        remember(userId, settings)
        return settings
    }

    suspend fun upsert(userId: String, given: RecommenderSettingsItem): RecommenderSettingsItem {
        val settings = given.bounded()
        DatabaseFactory.query { RecommenderUserDataRepository.saveSettings(userId, settings, System.currentTimeMillis()) }
        remember(userId, settings)
        return settings
    }

    suspend fun forget(userId: String) {
        runCatching { cache.delete(cacheKey(userId)) }
    }

    private suspend fun cached(userId: String): RecommenderSettingsItem? =
        runCatching { cache.get(cacheKey(userId)) }.getOrNull()
            ?.let { runCatching { CacheJson.decodeFromString(RecommenderSettingsItem.serializer(), it) }.getOrNull() }

    private suspend fun remember(userId: String, settings: RecommenderSettingsItem) {
        runCatching {
            cache.set(
                cacheKey(userId),
                CacheJson.encodeToString(RecommenderSettingsItem.serializer(), settings),
                config.settingsTtlSeconds,
            )
        }
    }

    private fun RecommenderSettingsItem.bounded(): RecommenderSettingsItem = copy(
        algorithmId = algorithmId?.take(MAX_ID_CHARS),
        parameters = parameters.filter { (id, value) -> !value.isString && id.length <= MAX_ID_CHARS }
            .entries.take(MAX_PARAMETERS).associate { it.key to it.value },
        mutedGroups = mutedGroups.bounded(),
        ignoredSignals = ignoredSignals.bounded(),
        pinnedGroups = pinnedGroups.bounded(),
        customInterests = customInterests.map { it.trim().replace(SPACES, " ").take(MAX_INTEREST_CHARS) }
            .filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(MAX_INTERESTS),
        unusedData = unusedData.bounded(),
        excludedItems = excludedItems.bounded(),
        retentionDays = retentionDays.filter { (id, days) -> id.length <= MAX_ID_CHARS && days in 1..MAX_RETENTION_DAYS }
            .entries.take(MAX_PARAMETERS).associate { it.key to it.value },
    )

    private fun List<String>.bounded(): List<String> =
        filter { it.length <= MAX_REF_CHARS }.distinct().take(MAX_CORRECTIONS)

    private fun cacheKey(userId: String): String = "recommender:settings:v1:$userId"

    private companion object {
        const val MAX_ID_CHARS = 64
        const val MAX_INTERESTS = 10
        const val MAX_INTEREST_CHARS = 60
        private val SPACES = Regex("\\s+")
        const val MAX_PARAMETERS = 64
        const val MAX_REF_CHARS = 300
        const val MAX_CORRECTIONS = 500
        const val MAX_RETENTION_DAYS = 3_650
    }
}
