package dev.typetype.server.services

import dev.typetype.server.cache.CacheJson
import dev.typetype.server.db.tables.RecommenderFeedbackTable
import dev.typetype.server.db.tables.RecommenderUserSettingsTable
import dev.typetype.server.models.RecommenderBackup
import dev.typetype.server.models.RecommenderFeedbackItem
import dev.typetype.server.models.RecommenderSettingsItem
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID

private val PARAMETERS = MapSerializer(String.serializer(), JsonPrimitive.serializer())

object RecommenderUserDataRepository {
    fun settings(userId: String): RecommenderSettingsItem? =
        RecommenderUserSettingsTable.selectAll()
            .where { RecommenderUserSettingsTable.userId eq userId }
            .singleOrNull()
            ?.let { row ->
                runCatching {
                    CacheJson.decodeFromString(RecommenderSettingsItem.serializer(), row[RecommenderUserSettingsTable.settingsJson])
                }.getOrNull()
            }

    fun saveSettings(userId: String, settings: RecommenderSettingsItem, now: Long) {
        RecommenderUserSettingsTable.upsert {
            it[RecommenderUserSettingsTable.userId] = userId
            it[enabled] = settings.enabled
            it[algorithmId] = settings.algorithmId
            it[settingsJson] = CacheJson.encodeToString(RecommenderSettingsItem.serializer(), settings)
            it[updatedAt] = now
        }
    }

    fun feedback(userId: String): List<RecommenderFeedbackItem> =
        RecommenderFeedbackTable.selectAll()
            .where { RecommenderFeedbackTable.userId eq userId }
            .orderBy(RecommenderFeedbackTable.createdAt to SortOrder.DESC)
            .map { it.toFeedback() }

    fun addFeedback(userId: String, item: RecommenderFeedbackItem): RecommenderFeedbackItem {
        val stored = item.copy(id = item.id.ifBlank { UUID.randomUUID().toString() })
        RecommenderFeedbackTable.insertIgnore {
            it[id] = stored.id
            it[RecommenderFeedbackTable.userId] = userId
            it[kind] = stored.kind
            it[verdict] = stored.verdict
            it[comment] = stored.comment
            it[videoUrl] = stored.videoUrl
            it[reason] = stored.reason
            it[title] = stored.title
            it[channelName] = stored.channelName
            it[thumbnailUrl] = stored.thumbnailUrl
            it[algorithmId] = stored.algorithmId
            it[parametersJson] = CacheJson.encodeToString(PARAMETERS, stored.parameters)
            it[createdAt] = stored.createdAt
        }
        return stored
    }

    fun setFeedbackReason(userId: String, id: String, reason: String?) {
        RecommenderFeedbackTable.update({
            (RecommenderFeedbackTable.userId eq userId) and (RecommenderFeedbackTable.id eq id)
        }) { it[RecommenderFeedbackTable.reason] = reason }
    }

    fun deleteFeedback(userId: String, id: String): Boolean =
        RecommenderFeedbackTable.deleteWhere {
            (RecommenderFeedbackTable.userId eq userId) and (RecommenderFeedbackTable.id eq id)
        } > 0

    fun export(userId: String): RecommenderBackup? {
        val settings = settings(userId)
        val feedback = feedback(userId)
        return if (settings == null && feedback.isEmpty()) null else RecommenderBackup(settings, feedback)
    }

    fun restore(userId: String, backup: RecommenderBackup, now: Long): Int {
        backup.settings?.let { saveSettings(userId, it, now) }
        // A fresh id for each opinion: the same export restored into two accounts must not collide.
        val known = feedback(userId).mapTo(HashSet()) { it.copy(id = "") }
        val added = backup.feedback.map { it.copy(id = "") }.distinct().filter { it !in known }
        added.forEach { addFeedback(userId, it) }
        return added.size + if (backup.settings != null) 1 else 0
    }

    fun enabledUsersByAlgorithm(): Map<String, Int> =
        RecommenderUserSettingsTable.selectAll()
            .where { RecommenderUserSettingsTable.enabled eq true }
            .groupingBy { it[RecommenderUserSettingsTable.algorithmId].orEmpty() }
            .eachCount()

    fun allFeedback(limit: Int): List<RecommenderFeedbackItem> =
        RecommenderFeedbackTable.selectAll()
            .orderBy(RecommenderFeedbackTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toFeedback() }

    private fun ResultRow.toFeedback(): RecommenderFeedbackItem = RecommenderFeedbackItem(
        id = this[RecommenderFeedbackTable.id],
        kind = this[RecommenderFeedbackTable.kind],
        verdict = this[RecommenderFeedbackTable.verdict],
        comment = this[RecommenderFeedbackTable.comment],
        videoUrl = this[RecommenderFeedbackTable.videoUrl],
        reason = this[RecommenderFeedbackTable.reason],
        title = this[RecommenderFeedbackTable.title],
        channelName = this[RecommenderFeedbackTable.channelName],
        thumbnailUrl = this[RecommenderFeedbackTable.thumbnailUrl],
        algorithmId = this[RecommenderFeedbackTable.algorithmId],
        parameters = runCatching {
            CacheJson.decodeFromString(PARAMETERS, this[RecommenderFeedbackTable.parametersJson])
        }.getOrDefault(emptyMap()),
        createdAt = this[RecommenderFeedbackTable.createdAt],
    )
}
