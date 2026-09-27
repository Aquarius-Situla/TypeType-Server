package dev.typetype.server.services

import dev.typetype.server.db.tables.FavoritesTable
import dev.typetype.server.db.tables.HistoryTable
import dev.typetype.server.db.tables.PlaylistVideosTable
import dev.typetype.server.db.tables.SubscriptionsTable
import dev.typetype.server.db.tables.WatchLaterTable
import dev.typetype.server.models.SubscriptionItem
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

object SubscriptionAvatarRepairer {
    fun repair(userId: String, items: List<SubscriptionItem>): List<SubscriptionItem> {
        val avatars = recoverableAvatars(
            userId = userId,
            items = items,
            maxRepairs = MAX_AVATAR_REPAIR_PER_REQUEST,
            maxSourceRows = MAX_AVATAR_SOURCE_ROWS,
        )
        avatars.forEach { (channelUrl, avatarUrl) -> updateAvatar(userId, channelUrl, avatarUrl) }
        return items.withAvatars(avatars)
    }

    fun repairImported(userId: String, items: List<SubscriptionItem>): List<SubscriptionItem> {
        val avatars = recoverableAvatars(
            userId = userId,
            items = items,
            maxRepairs = MAX_IMPORTED_AVATAR_REPAIR,
            maxSourceRows = MAX_IMPORTED_AVATAR_SOURCE_ROWS,
        )
        avatars.forEach { (channelUrl, avatarUrl) -> updateAvatar(userId, channelUrl, avatarUrl) }
        return items.withAvatars(avatars)
    }

    fun resolve(userId: String, items: List<SubscriptionItem>): List<SubscriptionItem> =
        items.withAvatars(
            recoverableAvatars(
                userId = userId,
                items = items,
                maxRepairs = MAX_AVATAR_REPAIR_PER_REQUEST,
                maxSourceRows = MAX_AVATAR_SOURCE_ROWS,
            ),
        )

    private fun recoverableAvatars(
        userId: String,
        items: List<SubscriptionItem>,
        maxRepairs: Int,
        maxSourceRows: Int,
    ): Map<String, String> {
        val candidateUrls = items.filter { it.avatarUrl.isBlank() }
            .map { it.channelUrl }
            .distinct()
        if (candidateUrls.isEmpty()) return emptyMap()
        val avatars = knownAvatars(userId, candidateUrls, maxSourceRows)
            .entries
            .take(maxRepairs)
            .associate { it.toPair() }
        return avatars
    }

    private fun List<SubscriptionItem>.withAvatars(avatars: Map<String, String>): List<SubscriptionItem> =
        if (avatars.isEmpty()) this else map { item -> avatars[item.channelUrl]?.let { item.copy(avatarUrl = it) } ?: item }

    private fun knownAvatars(
        userId: String,
        channelUrls: List<String>,
        maxSourceRows: Int,
    ): Map<String, String> {
        val avatars = linkedMapOf<String, String>()
        historyAvatars(userId, channelUrls, maxSourceRows).forEach { avatars.putIfAbsent(it.key, it.value) }
        playlistAvatars(userId, channelUrls, maxSourceRows).forEach { avatars.putIfAbsent(it.key, it.value) }
        watchLaterAvatars(userId, channelUrls, maxSourceRows).forEach { avatars.putIfAbsent(it.key, it.value) }
        favoriteAvatars(userId, channelUrls, maxSourceRows).forEach { avatars.putIfAbsent(it.key, it.value) }
        return avatars
    }

    private fun historyAvatars(
        userId: String,
        channelUrls: List<String>,
        maxSourceRows: Int,
    ): Map<String, String> = HistoryTable.selectAll()
        .where {
            avatarSourceFilter(
                userId,
                channelUrls,
                HistoryTable.userId,
                HistoryTable.channelUrl,
                HistoryTable.channelAvatar,
            )
        }
        .orderBy(HistoryTable.watchedAt to SortOrder.DESC)
        .limit(maxSourceRows)
        .associateAvatarRows(HistoryTable.channelUrl, HistoryTable.channelAvatar)

    private fun playlistAvatars(
        userId: String,
        channelUrls: List<String>,
        maxSourceRows: Int,
    ): Map<String, String> = PlaylistVideosTable.selectAll()
        .where {
            avatarSourceFilter(
                userId,
                channelUrls,
                PlaylistVideosTable.userId,
                PlaylistVideosTable.channelUrl,
                PlaylistVideosTable.channelAvatar,
            )
        }
        .limit(maxSourceRows)
        .associateAvatarRows(PlaylistVideosTable.channelUrl, PlaylistVideosTable.channelAvatar)

    private fun watchLaterAvatars(
        userId: String,
        channelUrls: List<String>,
        maxSourceRows: Int,
    ): Map<String, String> = WatchLaterTable.selectAll()
        .where {
            avatarSourceFilter(
                userId,
                channelUrls,
                WatchLaterTable.userId,
                WatchLaterTable.channelUrl,
                WatchLaterTable.channelAvatar,
            )
        }
        .orderBy(WatchLaterTable.addedAt to SortOrder.DESC)
        .limit(maxSourceRows)
        .associateAvatarRows(WatchLaterTable.channelUrl, WatchLaterTable.channelAvatar)

    private fun favoriteAvatars(
        userId: String,
        channelUrls: List<String>,
        maxSourceRows: Int,
    ): Map<String, String> = FavoritesTable.selectAll()
        .where {
            avatarSourceFilter(
                userId,
                channelUrls,
                FavoritesTable.userId,
                FavoritesTable.channelUrl,
                FavoritesTable.channelAvatar,
            )
        }
        .orderBy(FavoritesTable.favoritedAt to SortOrder.DESC)
        .limit(maxSourceRows)
        .associateAvatarRows(FavoritesTable.channelUrl, FavoritesTable.channelAvatar)

    private fun updateAvatar(userId: String, channelUrl: String, avatarUrl: String): Int = SubscriptionsTable.update({
        (SubscriptionsTable.userId eq userId) and (SubscriptionsTable.channelUrl eq channelUrl) and (SubscriptionsTable.avatarUrl eq "")
    }) {
        it[SubscriptionsTable.avatarUrl] = avatarUrl
    }

    private fun avatarSourceFilter(
        userId: String,
        channelUrls: List<String>,
        userColumn: Column<String>,
        urlColumn: Column<String>,
        avatarColumn: Column<String>,
    ) = (userColumn eq userId) and (urlColumn inList channelUrls) and (avatarColumn neq "")

    private fun Iterable<ResultRow>.associateAvatarRows(
        urlColumn: Column<String>,
        avatarColumn: Column<String>,
    ): Map<String, String> = mapNotNull { row ->
        val avatar = row[avatarColumn].trim()
        if (avatar.isProxyableAvatar()) ChannelUrlCanonicalizer.canonicalize(row[urlColumn]) to avatar else null
    }.distinctBy { it.first }.toMap()

    private fun String.isProxyableAvatar(): Boolean = startsWith("https://") || startsWith("http://")

    private const val MAX_AVATAR_REPAIR_PER_REQUEST = 25
    private const val MAX_AVATAR_SOURCE_ROWS = 100
    private const val MAX_IMPORTED_AVATAR_REPAIR = 5_000
    private const val MAX_IMPORTED_AVATAR_SOURCE_ROWS = 20_000
}
