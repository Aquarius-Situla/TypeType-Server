package dev.typetype.server.services

import dev.typetype.server.db.DatabaseFactory
import dev.typetype.server.db.tables.BiliBiliSessionsTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

internal data class BiliBiliSessionRefreshLease(
    val userId: String,
    val encryptedCookies: String,
    val encryptedRefreshToken: String,
    val encryptedPendingConfirmToken: String?,
    val claimedUntil: Long,
)

internal class BiliBiliSessionRefreshStore {
    suspend fun claimDueSessions(now: Long): List<BiliBiliSessionRefreshLease> = DatabaseFactory.query {
        val cutoff = now - CHECK_INTERVAL_MS
        var claimedCount = 0
        BiliBiliSessionsTable.selectAll()
            .where {
                (BiliBiliSessionsTable.status eq BiliBiliSessionStatus.Connected.value) and
                    BiliBiliSessionsTable.encryptedRefreshToken.isNotNull() and
                    (BiliBiliSessionsTable.encryptedPendingConfirmToken.isNotNull() or
                        (BiliBiliSessionsTable.refreshCheckedAt lessEq cutoff))
            }
            .orderBy(BiliBiliSessionsTable.refreshCheckedAt to SortOrder.ASC)
            .limit(MAX_CANDIDATES)
            .toList()
            .mapNotNull { row ->
                if (claimedCount >= MAX_BATCH_SIZE) return@mapNotNull null
                val pending = row[BiliBiliSessionsTable.encryptedPendingConfirmToken]
                if (pending == null && row[BiliBiliSessionsTable.refreshCheckedAt] > cutoff) return@mapNotNull null
                val oldLease = row[BiliBiliSessionsTable.refreshClaimedUntil]
                if (oldLease > now) return@mapNotNull null
                val userId = row[BiliBiliSessionsTable.userId]
                val refreshToken = row[BiliBiliSessionsTable.encryptedRefreshToken] ?: return@mapNotNull null
                val leaseUntil = now + LEASE_DURATION_MS
                val updated = BiliBiliSessionsTable.update({
                    (BiliBiliSessionsTable.userId eq userId) and
                        (BiliBiliSessionsTable.status eq BiliBiliSessionStatus.Connected.value) and
                        (BiliBiliSessionsTable.refreshClaimedUntil eq oldLease) and
                        (BiliBiliSessionsTable.encryptedRefreshToken eq refreshToken)
                }) {
                    it[BiliBiliSessionsTable.refreshClaimedUntil] = leaseUntil
                }
                if (updated == 0) return@mapNotNull null
                claimedCount += 1
                BiliBiliSessionRefreshLease(
                    userId = userId,
                    encryptedCookies = row[BiliBiliSessionsTable.encryptedCookies],
                    encryptedRefreshToken = refreshToken,
                    encryptedPendingConfirmToken = pending,
                    claimedUntil = leaseUntil,
                )
            }
    }

    suspend fun persistRotation(
        lease: BiliBiliSessionRefreshLease,
        encryptedCookies: String,
        encryptedRefreshToken: String,
        encryptedOldRefreshToken: String,
        expiresAt: Long,
        now: Long,
    ): Boolean = DatabaseFactory.query {
        BiliBiliSessionsTable.update({ leaseCondition(lease) }) {
            it[BiliBiliSessionsTable.encryptedCookies] = encryptedCookies
            it[BiliBiliSessionsTable.encryptedRefreshToken] = encryptedRefreshToken
            it[BiliBiliSessionsTable.encryptedPendingConfirmToken] = encryptedOldRefreshToken
            it[BiliBiliSessionsTable.expiresAt] = expiresAt
            it[BiliBiliSessionsTable.updatedAt] = now
            it[BiliBiliSessionsTable.refreshCheckedAt] = now
        } > 0
    }

    suspend fun completeCheck(lease: BiliBiliSessionRefreshLease, checkedAt: Long): Boolean = DatabaseFactory.query {
        BiliBiliSessionsTable.update({ leaseCondition(lease) }) {
            it[BiliBiliSessionsTable.refreshCheckedAt] = checkedAt
            it[BiliBiliSessionsTable.refreshClaimedUntil] = 0
            it[BiliBiliSessionsTable.encryptedPendingConfirmToken] = null
        } > 0
    }

    suspend fun markNeedsReconnect(lease: BiliBiliSessionRefreshLease, now: Long): Boolean = DatabaseFactory.query {
        BiliBiliSessionsTable.update({ leaseCondition(lease) }) {
            it[BiliBiliSessionsTable.status] = BiliBiliSessionStatus.NeedsReconnect.value
            it[BiliBiliSessionsTable.updatedAt] = now
            it[BiliBiliSessionsTable.encryptedRefreshToken] = null
            it[BiliBiliSessionsTable.encryptedPendingConfirmToken] = null
            it[BiliBiliSessionsTable.refreshClaimedUntil] = 0
        } > 0
    }

    suspend fun release(lease: BiliBiliSessionRefreshLease): Unit = DatabaseFactory.query {
        BiliBiliSessionsTable.update({ leaseCondition(lease) }) {
            it[BiliBiliSessionsTable.refreshClaimedUntil] = 0
        }
    }

    private fun leaseCondition(lease: BiliBiliSessionRefreshLease): Op<Boolean> =
        (BiliBiliSessionsTable.userId eq lease.userId) and
            (BiliBiliSessionsTable.refreshClaimedUntil eq lease.claimedUntil) and
            (BiliBiliSessionsTable.status eq BiliBiliSessionStatus.Connected.value)

    private companion object {
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1_000
        const val LEASE_DURATION_MS = 30L * 60 * 1_000
        const val MAX_CANDIDATES = 1_000
        const val MAX_BATCH_SIZE = 20
    }
}
