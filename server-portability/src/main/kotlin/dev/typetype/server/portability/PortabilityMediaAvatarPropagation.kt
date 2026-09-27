package dev.typetype.server.portability

import dev.typetype.server.db.tables.FavoritesTable
import dev.typetype.server.db.tables.HistoryTable
import dev.typetype.server.db.tables.PlaylistVideosTable
import dev.typetype.server.db.tables.SubscriptionsTable
import dev.typetype.server.db.tables.WatchLaterTable
import dev.typetype.server.models.SubscriptionItem
import dev.typetype.server.services.ChannelUrlCanonicalizer
import dev.typetype.server.services.SubscriptionAvatarRepairer
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

object PortabilityMediaAvatarPropagation {
    fun propagate(userId: String): Int {
        val subscriptions = SubscriptionsTable.selectAll()
            .where {
                SubscriptionsTable.userId eq userId
            }
            .limit(MAX_SUBSCRIPTIONS)
            .map {
                SubscriptionItem(
                    channelUrl = it[SubscriptionsTable.channelUrl],
                    name = it[SubscriptionsTable.name],
                    avatarUrl = it[SubscriptionsTable.avatarUrl],
                    subscribedAt = it[SubscriptionsTable.subscribedAt],
                )
            }
        val avatars = SubscriptionAvatarRepairer.repairImported(userId, subscriptions)
            .filter { it.avatarUrl.isNotBlank() }
            .associate {
                ChannelUrlCanonicalizer.canonicalize(it.channelUrl) to it.avatarUrl
            }
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
