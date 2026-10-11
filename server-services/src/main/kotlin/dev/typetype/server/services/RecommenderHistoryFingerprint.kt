package dev.typetype.server.services

import dev.typetype.server.db.DatabaseFactory
import dev.typetype.server.db.tables.FavoritesTable
import dev.typetype.server.db.tables.HistoryTable
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.select
import java.security.MessageDigest

private const val KEY_HEX_CHARS = 32

object RecommenderHistoryFingerprint {
    suspend fun of(userId: String): String = DatabaseFactory.query {
        val watches = HistoryTable.id.count()
        val lastWatch = HistoryTable.watchedAt.max()
        val history = HistoryTable.select(watches, lastWatch).where { HistoryTable.userId eq userId }.single()
        val likes = FavoritesTable.videoUrl.count()
        val lastLike = FavoritesTable.favoritedAt.max()
        val favorites = FavoritesTable.select(likes, lastLike).where { FavoritesTable.userId eq userId }.single()
        digest(
            listOf(userId, history[watches], history[lastWatch] ?: 0L, favorites[likes], favorites[lastLike] ?: 0L)
                .joinToString("|"),
        )
    }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(KEY_HEX_CHARS)
}
