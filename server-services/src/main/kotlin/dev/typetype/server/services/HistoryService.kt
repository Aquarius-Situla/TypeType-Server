package dev.typetype.server.services

import dev.typetype.server.db.DatabaseFactory
import dev.typetype.server.db.tables.HistoryTable
import dev.typetype.server.db.tables.ProgressTable
import dev.typetype.server.db.tables.SubscriptionsTable
import dev.typetype.server.models.HistoryItem
import org.jetbrains.exposed.v1.core.LowerCase
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

class HistoryService {
    suspend fun getAll(userId: String): List<HistoryItem> = DatabaseFactory.query {
        val rows = HistoryTable.selectAll()
            .where { HistoryTable.userId eq userId }
            .orderBy(HistoryTable.watchedAt to SortOrder.DESC, HistoryTable.id to SortOrder.DESC)
            .toList()
        HistoryProgressMapper.toHistoryItemsForExport(userId, rows)
    }.withSubscriptionAvatars(userId)

    suspend fun search(userId: String, q: String?, from: Long?, to: Long?, limit: Int, offset: Int): Pair<List<HistoryItem>, Long> {
        val (items, total) = DatabaseFactory.query {
            val query = HistoryTable.selectAll().where { HistoryTable.userId eq userId }
            if (!q.isNullOrBlank()) {
                val pattern = "%${q.lowercase()}%"
                query.andWhere { (LowerCase(HistoryTable.title) like pattern) or (LowerCase(HistoryTable.channelName) like pattern) }
            }
            if (from != null) query.andWhere { HistoryTable.watchedAt greaterEq from }
            if (to != null) query.andWhere { HistoryTable.watchedAt less to }
            val total = query.count()
            val rows = query.orderBy(HistoryTable.watchedAt to SortOrder.DESC, HistoryTable.id to SortOrder.DESC).limit(limit).offset(offset.toLong()).toList()
            HistoryProgressMapper.toHistoryItems(userId, rows) to total
        }
        return items.withSubscriptionAvatars(userId) to total
    }

    private suspend fun List<HistoryItem>.withSubscriptionAvatars(userId: String): List<HistoryItem> {
        val missingUrls = filter { it.channelAvatar.isBlank() && it.channelUrl.isNotBlank() }
            .map { ChannelUrlCanonicalizer.canonicalize(it.channelUrl) }
            .distinct()
        if (missingUrls.isEmpty()) return this
        val avatars = DatabaseFactory.query {
            SubscriptionsTable.selectAll()
                .where {
                    (SubscriptionsTable.userId eq userId) and
                        (SubscriptionsTable.channelUrl inList missingUrls) and
                        (SubscriptionsTable.avatarUrl neq "")
                }
                .map {
                    ChannelUrlCanonicalizer.canonicalize(it[SubscriptionsTable.channelUrl]) to
                        it[SubscriptionsTable.avatarUrl]
                }
                .toMap()
        }
        if (avatars.isEmpty()) return this
        return map { item ->
            if (item.channelAvatar.isNotBlank()) item
            else avatars[ChannelUrlCanonicalizer.canonicalize(item.channelUrl)]?.let {
                item.copy(channelAvatar = it)
            } ?: item
        }
    }

    suspend fun add(userId: String, item: HistoryItem): HistoryItem = insert(userId, item, System.currentTimeMillis())

    suspend fun addImported(userId: String, item: HistoryItem): HistoryItem = insert(userId, item, item.watchedAt.takeIf { it > 0 } ?: System.currentTimeMillis())

    suspend fun addImportedBatch(userId: String, items: List<HistoryItem>): Int {
        if (items.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val rows = items.map { rawItem ->
            val item = YoutubeTypeTypeMapper.historyItem(rawItem)
            val watchedAt = item.watchedAt.takeIf { it > 0 } ?: now
            Triple(UUID.randomUUID().toString(), item, watchedAt)
        }
        DatabaseFactory.query {
            val progressByUrl = HistoryProgressMapper.savedProgressSeconds(userId, rows.map { it.second.url })
            HistoryTable.batchInsert(data = rows, shouldReturnGeneratedValues = false) { (id, item, watchedAt) ->
                this[HistoryTable.id] = id
                this[HistoryTable.userId] = userId
                this[HistoryTable.url] = item.url
                this[HistoryTable.title] = item.title
                this[HistoryTable.thumbnail] = item.thumbnail
                this[HistoryTable.channelName] = item.channelName
                this[HistoryTable.channelUrl] = item.channelUrl
                this[HistoryTable.channelAvatar] = item.channelAvatar
                this[HistoryTable.duration] = item.duration
                this[HistoryTable.progress] = maxOf(item.progress, progressByUrl[item.url] ?: 0L)
                this[HistoryTable.watchedAt] = watchedAt
            }
        }
        return rows.size
    }

    suspend fun dedupKeys(userId: String): Set<Pair<String, Long>> = DatabaseFactory.query { HistoryTable.selectAll().where { HistoryTable.userId eq userId }.map { it[HistoryTable.url] to it[HistoryTable.watchedAt] }.toSet() }

    suspend fun delete(userId: String, id: String): Boolean = DatabaseFactory.query {
        val url = HistoryTable.selectAll()
            .where { (HistoryTable.id eq id) and (HistoryTable.userId eq userId) }
            .singleOrNull()
            ?.get(HistoryTable.url)
            ?: return@query false
        val deleted = HistoryTable.deleteWhere { (HistoryTable.id eq id) and (HistoryTable.userId eq userId) } > 0
        if (deleted) deleteProgress(userId, url)
        deleted
    }

    suspend fun deleteAll(userId: String): Unit = DatabaseFactory.query {
        HistoryTable.deleteWhere { HistoryTable.userId eq userId }
        deleteAllProgress(userId)
    }

    private suspend fun insert(userId: String, item: HistoryItem, watchedAt: Long): HistoryItem {
        val id = UUID.randomUUID().toString()
        val mapped = YoutubeTypeTypeMapper.historyItem(item)
        val progress = DatabaseFactory.query { maxOf(mapped.progress, HistoryProgressMapper.savedProgressSeconds(userId, mapped.url) ?: 0L) }
        DatabaseFactory.query {
            HistoryTable.insert {
                it[HistoryTable.id] = id
                it[HistoryTable.userId] = userId
                it[url] = mapped.url
                it[title] = mapped.title
                it[thumbnail] = mapped.thumbnail
                it[channelName] = mapped.channelName
                it[channelUrl] = mapped.channelUrl
                it[channelAvatar] = mapped.channelAvatar
                it[duration] = mapped.duration
                it[HistoryTable.progress] = progress
                it[HistoryTable.watchedAt] = watchedAt
            }
        }
        return mapped.copy(id = id, progress = progress, watchedAt = watchedAt)
    }

    private fun deleteProgress(userId: String, videoUrl: String) {
        ProgressTable.deleteWhere { (ProgressTable.userId eq userId) and (ProgressTable.videoUrl eq videoUrl) }
    }

    private fun deleteAllProgress(userId: String) {
        ProgressTable.deleteWhere { ProgressTable.userId eq userId }
    }
}
