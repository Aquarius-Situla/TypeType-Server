package dev.typetype.server.portability

import dev.typetype.server.db.tables.FavoritesTable
import dev.typetype.server.db.tables.HistoryTable
import dev.typetype.server.db.tables.PlaylistVideosTable
import dev.typetype.server.db.tables.SubscriptionsTable
import dev.typetype.server.db.tables.WatchLaterTable
import dev.typetype.server.services.ChannelUrlCanonicalizer
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

object PortabilityMediaAvatarPropagation {
    fun propagate(userId: String): Int {
        val avatars = SubscriptionsTable.selectAll()
            .where {
                (SubscriptionsTable.userId eq userId) and
                    (SubscriptionsTable.avatarUrl neq "")
            }
            .limit(MAX_SUBSCRIPTIONS)
            .map {
                ChannelUrlCanonicalizer.canonicalize(it[SubscriptionsTable.channelUrl]) to
                    it[SubscriptionsTable.avatarUrl]
            }
            .toMap()
        return avatars.entries.sumOf { (channelUrl, avatarUrl) ->
            HistoryTable.update({
                (HistoryTable.userId eq userId) and
                    (HistoryTable.channelUrl eq channelUrl) and
                    (HistoryTable.channelAvatar eq "")
            }) { it[HistoryTable.channelAvatar] = avatarUrl } +
                PlaylistVideosTable.update({
                    (PlaylistVideosTable.userId eq userId) and
                        (PlaylistVideosTable.channelUrl eq channelUrl) and
                        (PlaylistVideosTable.channelAvatar eq "")
                }) { it[PlaylistVideosTable.channelAvatar] = avatarUrl } +
                WatchLaterTable.update({
                    (WatchLaterTable.userId eq userId) and
                        (WatchLaterTable.channelUrl eq channelUrl) and
                        (WatchLaterTable.channelAvatar eq "")
                }) { it[WatchLaterTable.channelAvatar] = avatarUrl } +
                FavoritesTable.update({
                    (FavoritesTable.userId eq userId) and
                        (FavoritesTable.channelUrl eq channelUrl) and
                        (FavoritesTable.channelAvatar eq "")
                }) { it[FavoritesTable.channelAvatar] = avatarUrl }
        }
    }

    private const val MAX_SUBSCRIPTIONS = 2_000
}
