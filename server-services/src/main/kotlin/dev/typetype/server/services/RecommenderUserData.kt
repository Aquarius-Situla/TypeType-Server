package dev.typetype.server.services

import dev.typetype.server.db.DatabaseFactory
import dev.typetype.server.db.tables.FavoritesTable
import dev.typetype.server.db.tables.HistoryTable
import dev.typetype.server.db.tables.ProgressTable
import dev.typetype.server.models.FavoriteItem
import dev.typetype.server.models.HistoryItem
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notLike
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

private const val SHORTS_PATTERN = "%/shorts/%"
private const val URLS_PER_QUERY = 1_000

object RecommenderUserData {
    suspend fun watches(userId: String, limit: Int): List<HistoryItem> = DatabaseFactory.query {
        HistoryTable.select(
            HistoryTable.id, HistoryTable.url, HistoryTable.title, HistoryTable.channelName,
            HistoryTable.channelUrl, HistoryTable.duration, HistoryTable.progress, HistoryTable.watchedAt,
        )
            .where { HistoryTable.userId eq userId }
            .orderBy(HistoryTable.watchedAt to SortOrder.DESC, HistoryTable.id to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                HistoryItem(
                    id = row[HistoryTable.id],
                    url = row[HistoryTable.url],
                    title = row[HistoryTable.title],
                    thumbnail = "",
                    channelName = row[HistoryTable.channelName],
                    channelUrl = row[HistoryTable.channelUrl],
                    duration = row[HistoryTable.duration],
                    progress = row[HistoryTable.progress],
                    watchedAt = row[HistoryTable.watchedAt],
                )
            }
    }

    suspend fun likes(userId: String, limit: Int): List<FavoriteItem> = DatabaseFactory.query {
        FavoritesTable.select(
            FavoritesTable.videoUrl, FavoritesTable.favoritedAt, FavoritesTable.title,
            FavoritesTable.channelName, FavoritesTable.channelUrl,
        )
            .where { FavoritesTable.userId eq userId }
            .orderBy(FavoritesTable.favoritedAt to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                FavoriteItem(
                    videoUrl = row[FavoritesTable.videoUrl],
                    favoritedAt = row[FavoritesTable.favoritedAt],
                    title = row[FavoritesTable.title],
                    channelName = row[FavoritesTable.channelName],
                    channelUrl = row[FavoritesTable.channelUrl],
                )
            }
    }

    suspend fun positionsMs(userId: String, urls: List<String>): Map<String, Long> = DatabaseFactory.query {
        urls.distinct().chunked(URLS_PER_QUERY).flatMap { chunk ->
            ProgressTable.select(ProgressTable.videoUrl, ProgressTable.position)
                .where { (ProgressTable.userId eq userId) and (ProgressTable.videoUrl inList chunk) }
                .map { it[ProgressTable.videoUrl] to it[ProgressTable.position] }
        }.toMap()
    }

    suspend fun watchCount(userId: String, shorts: Boolean): Pair<Int, Long?> = DatabaseFactory.query {
        val count = HistoryTable.id.count()
        val oldest = HistoryTable.watchedAt.min()
        val row = HistoryTable.select(count, oldest).where {
            (HistoryTable.userId eq userId) and
                (if (shorts) HistoryTable.url like SHORTS_PATTERN else HistoryTable.url notLike SHORTS_PATTERN)
        }.single()
        row[count].toInt() to row[oldest]
    }

    suspend fun newestWatches(userId: String, shorts: Boolean, limit: Int): List<HistoryItem> = DatabaseFactory.query {
        HistoryTable.selectAll().where {
            (HistoryTable.userId eq userId) and
                (if (shorts) HistoryTable.url like SHORTS_PATTERN else HistoryTable.url notLike SHORTS_PATTERN)
        }
            .orderBy(HistoryTable.watchedAt to SortOrder.DESC, HistoryTable.id to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                HistoryItem(
                    id = row[HistoryTable.id],
                    url = row[HistoryTable.url],
                    title = row[HistoryTable.title],
                    thumbnail = row[HistoryTable.thumbnail],
                    channelName = row[HistoryTable.channelName],
                    channelUrl = row[HistoryTable.channelUrl],
                    duration = row[HistoryTable.duration],
                    progress = row[HistoryTable.progress],
                    watchedAt = row[HistoryTable.watchedAt],
                )
            }
    }

    suspend fun channelAvatarsByWatchId(userId: String, ids: Collection<String>): Map<String, String> = DatabaseFactory.query {
        val channelOf = ids.distinct().chunked(URLS_PER_QUERY).flatMap { chunk ->
            HistoryTable.select(HistoryTable.id, HistoryTable.channelUrl)
                .where { (HistoryTable.userId eq userId) and (HistoryTable.id inList chunk) }
                .map { row -> row[HistoryTable.id] to row[HistoryTable.channelUrl] }
        }.toMap()
        val avatarOf = channelOf.values.distinct().chunked(URLS_PER_QUERY).flatMap { chunk ->
            HistoryTable.select(HistoryTable.channelUrl, HistoryTable.channelAvatar).withDistinct()
                .where {
                    (HistoryTable.userId eq userId) and (HistoryTable.channelUrl inList chunk) and
                        (HistoryTable.channelAvatar neq "")
                }
                .map { row -> row[HistoryTable.channelUrl] to row[HistoryTable.channelAvatar] }
        }.toMap()
        channelOf.mapNotNull { (id, channel) -> avatarOf[channel]?.let { id to it } }.toMap()
    }

    suspend fun watchesById(userId: String, ids: Collection<String>): List<HistoryItem> = DatabaseFactory.query {
        ids.distinct().chunked(URLS_PER_QUERY).flatMap { chunk ->
            HistoryTable.select(
                HistoryTable.id, HistoryTable.url, HistoryTable.title, HistoryTable.thumbnail, HistoryTable.channelName,
            )
                .where { (HistoryTable.userId eq userId) and (HistoryTable.id inList chunk) }
                .map { row ->
                    HistoryItem(
                        id = row[HistoryTable.id],
                        url = row[HistoryTable.url],
                        title = row[HistoryTable.title],
                        thumbnail = row[HistoryTable.thumbnail],
                        channelName = row[HistoryTable.channelName],
                        channelUrl = "",
                        duration = 0L,
                        progress = 0L,
                        watchedAt = 0L,
                    )
                }
        }
    }

    suspend fun likeCount(userId: String): Pair<Int, Long?> = DatabaseFactory.query {
        val count = FavoritesTable.videoUrl.count()
        val oldest = FavoritesTable.favoritedAt.min()
        val row = FavoritesTable.select(count, oldest).where { FavoritesTable.userId eq userId }.single()
        row[count].toInt() to row[oldest]
    }
}
