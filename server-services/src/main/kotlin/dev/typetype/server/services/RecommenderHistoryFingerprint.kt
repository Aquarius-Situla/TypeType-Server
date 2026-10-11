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
    suspend fun of(
        userId: String,
        includeHistory: Boolean = true,
        includeFavorites: Boolean = true,
    ): String = DatabaseFactory.query {
        val parts = mutableListOf(userId)
        if (includeHistory) {
            val watches = HistoryTable.id.count()
            val lastWatch = HistoryTable.watchedAt.max()
            val history = HistoryTable.select(watches, lastWatch)
                .where { HistoryTable.userId eq userId }.single()
            parts.addAll(listOf("history", history[watches].toString(), (history[lastWatch] ?: 0L).toString()))
        }
        if (includeFavorites) {
            val likes = FavoritesTable.videoUrl.count()
            val lastLike = FavoritesTable.favoritedAt.max()
            val favorites = FavoritesTable.select(likes, lastLike)
                .where { FavoritesTable.userId eq userId }.single()
            parts.addAll(listOf("favorites", favorites[likes].toString(), (favorites[lastLike] ?: 0L).toString()))
        }
        digest(parts.joinToString("|"))
    }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(KEY_HEX_CHARS)
}
