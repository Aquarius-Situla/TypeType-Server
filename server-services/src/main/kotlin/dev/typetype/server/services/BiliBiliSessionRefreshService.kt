package dev.typetype.server.services

import kotlinx.coroutines.CancellationException

internal class BiliBiliSessionRefreshService(
    private val crypto: BiliBiliSessionCrypto,
    private val refreshStore: BiliBiliSessionRefreshStore,
    private val client: BiliBiliCookieRefreshClient,
    private val nowMillis: () -> Long,
) {
    suspend fun refreshDueSessions(): Int {
        val leases = refreshStore.claimDueSessions(nowMillis())
        leases.forEach { lease -> refresh(lease) }
        return leases.size
    }

    private suspend fun refresh(lease: BiliBiliSessionRefreshLease) {
        try {
            val cookies = decrypt(lease.encryptedCookies)
            if (cookies == null) {
                refreshStore.markNeedsReconnect(lease, nowMillis())
                return
            }
            val pendingToken = lease.encryptedPendingConfirmToken?.let(::decrypt)
            if (lease.encryptedPendingConfirmToken != null && pendingToken == null) {
                refreshStore.markNeedsReconnect(lease, nowMillis())
                return
            }
            if (pendingToken != null) {
                finishConfirmation(lease, cookies, pendingToken)
                return
            }
            val refreshToken = decrypt(lease.encryptedRefreshToken)
            if (refreshToken == null) {
                refreshStore.markNeedsReconnect(lease, nowMillis())
                return
            }
            when (val result = client.inspectAndRotate(cookies, refreshToken)) {
                BiliBiliCookieRefreshResult.NotRequired -> refreshStore.completeCheck(lease, nowMillis())
                BiliBiliCookieRefreshResult.NeedsReconnect -> refreshStore.markNeedsReconnect(lease, nowMillis())
                BiliBiliCookieRefreshResult.RetryableFailure -> refreshStore.release(lease)
                is BiliBiliCookieRefreshResult.Rotated -> {
                    val encryptedCookies = crypto.encrypt(result.cookies)
                    val encryptedToken = crypto.encrypt(result.refreshToken)
                    val encryptedOldToken = crypto.encrypt(refreshToken)
                    if (!refreshStore.persistRotation(
                            lease,
                            encryptedCookies,
                            encryptedToken,
                            encryptedOldToken,
                            result.expiresAt,
                            nowMillis(),
                        )
                    ) return
                    val rotatedLease = lease.copy(
                        encryptedCookies = encryptedCookies,
                        encryptedRefreshToken = encryptedToken,
                        encryptedPendingConfirmToken = encryptedOldToken,
                    )
                    finishConfirmation(rotatedLease, result.cookies, refreshToken)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            refreshStore.release(lease)
        }
    }

    private suspend fun finishConfirmation(
        lease: BiliBiliSessionRefreshLease,
        cookies: String,
        oldRefreshToken: String,
    ) {
        when (client.confirmRotation(cookies, oldRefreshToken)) {
            BiliBiliCookieRefreshConfirmation.Confirmed -> refreshStore.completeCheck(lease, nowMillis())
            BiliBiliCookieRefreshConfirmation.NeedsReconnect -> refreshStore.markNeedsReconnect(lease, nowMillis())
            BiliBiliCookieRefreshConfirmation.RetryableFailure -> refreshStore.release(lease)
        }
    }

    private fun decrypt(value: String): String? = try {
        crypto.decrypt(value)
    } catch (_: Exception) {
        null
    }
}
