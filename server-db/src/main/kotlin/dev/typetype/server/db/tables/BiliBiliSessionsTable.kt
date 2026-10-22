package dev.typetype.server.db.tables

import org.jetbrains.exposed.v1.core.Table

object BiliBiliSessionsTable : Table("bilibili_sessions") {
    val userId = text("user_id")
    val encryptedCookies = text("encrypted_cookies")
    val status = text("status")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val lastUsedAt = long("last_used_at").default(0)
    val expiresAt = long("expires_at").default(0)
    val encryptedRefreshToken = text("encrypted_refresh_token").nullable()
    val encryptedPendingConfirmToken = text("encrypted_pending_confirm_token").nullable()
    val refreshCheckedAt = long("refresh_checked_at").default(0)
    val refreshClaimedUntil = long("refresh_claimed_until").default(0)
    override val primaryKey = PrimaryKey(userId)
}
