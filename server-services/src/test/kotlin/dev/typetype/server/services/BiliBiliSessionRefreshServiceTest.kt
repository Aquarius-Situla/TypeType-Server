package dev.typetype.server.services

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BiliBiliSessionRefreshServiceTest {
    @Test
    fun `persisted rotation is retried without rotating again`() = runTest {
        val crypto = BiliBiliSessionCrypto.fromSecret("test-secret-key-at-least-32-chars!!")
        val initialLease = BiliBiliSessionRefreshLease(
            userId = "user-1",
            encryptedCookies = crypto.encrypt("SESSDATA=old; bili_jct=old-csrf"),
            encryptedRefreshToken = crypto.encrypt("old-refresh-token"),
            encryptedPendingConfirmToken = null,
            claimedUntil = 99_000L,
        )
        val refreshStore = mockk<BiliBiliSessionRefreshStore>()
        val client = mockk<BiliBiliCookieRefreshClient>()
        val encryptedCookies = slot<String>()
        val encryptedRefreshToken = slot<String>()
        val encryptedPendingToken = slot<String>()
        var claimCount = 0

        coEvery { refreshStore.claimDueSessions(1_000L) } coAnswers {
            claimCount += 1
            if (claimCount == 1) listOf(initialLease) else listOf(
                initialLease.copy(
                    encryptedCookies = encryptedCookies.captured,
                    encryptedRefreshToken = encryptedRefreshToken.captured,
                    encryptedPendingConfirmToken = encryptedPendingToken.captured,
                ),
            )
        }
        coEvery {
            client.inspectAndRotate("SESSDATA=old; bili_jct=old-csrf", "old-refresh-token")
        } returns BiliBiliCookieRefreshResult.Rotated(
            cookies = "SESSDATA=new; bili_jct=new-csrf",
            refreshToken = "new-refresh-token",
            expiresAt = 5_000L,
        )
        coEvery {
            refreshStore.persistRotation(
                initialLease,
                capture(encryptedCookies),
                capture(encryptedRefreshToken),
                capture(encryptedPendingToken),
                5_000L,
                1_000L,
            )
        } returns true
        coEvery { client.confirmRotation("SESSDATA=new; bili_jct=new-csrf", "old-refresh-token") } returnsMany listOf(
            BiliBiliCookieRefreshConfirmation.RetryableFailure,
            BiliBiliCookieRefreshConfirmation.Confirmed,
        )
        coEvery { refreshStore.release(any()) } returns Unit
        coEvery { refreshStore.completeCheck(any(), 1_000L) } returns true

        val service = BiliBiliSessionRefreshService(
            crypto,
            refreshStore,
            client,
        ) { 1_000L }

        assertEquals(1, service.refreshDueSessions())
        assertEquals("SESSDATA=new; bili_jct=new-csrf", crypto.decrypt(encryptedCookies.captured))
        assertEquals("new-refresh-token", crypto.decrypt(encryptedRefreshToken.captured))
        assertEquals("old-refresh-token", crypto.decrypt(encryptedPendingToken.captured))

        assertEquals(1, service.refreshDueSessions())

        coVerify(exactly = 1) { client.inspectAndRotate(any(), any()) }
        coVerify(exactly = 2) { client.confirmRotation("SESSDATA=new; bili_jct=new-csrf", "old-refresh-token") }
        coVerify(exactly = 1) { refreshStore.release(match { it.encryptedPendingConfirmToken == encryptedPendingToken.captured }) }
        coVerify(exactly = 1) { refreshStore.completeCheck(match { it.encryptedPendingConfirmToken == encryptedPendingToken.captured }, 1_000L) }
    }
}
