package dev.typetype.server

import dev.typetype.server.services.YoutubeSessionStore
import dev.typetype.server.services.YoutubeSessionCrypto
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class YoutubeSessionStoreOauthTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initDb() = TestDatabase.setup()

        private val crypto = YoutubeSessionCrypto.fromSecret("x".repeat(48))
    }

    @BeforeEach
    fun clean() = TestDatabase.truncateAll()

    @Test
    fun `stores an oauth session encrypted and isolated per user`() = runBlocking {
        val store = YoutubeSessionStore()
        store.storeOauthSession(
            userId = "alice",
            encryptedAccessToken = crypto.encrypt("access-a"),
            encryptedRefreshToken = crypto.encrypt("refresh-a"),
            accessExpiresAt = 1_000L,
            accountLabel = "Alice",
        )
        store.storeOauthSession(
            userId = "bob",
            encryptedAccessToken = crypto.encrypt("access-b"),
            encryptedRefreshToken = crypto.encrypt("refresh-b"),
            accessExpiresAt = 2_000L,
            accountLabel = "Bob",
        )

        val alice = store.oauthEncrypted("alice")
        assertEquals("access-a", crypto.decrypt(alice!!.accessToken))
        assertEquals("refresh-a", crypto.decrypt(alice.refreshToken))
        assertEquals("Bob", store.status("bob").accountLabel)
        assertEquals("oauth_device", store.status("bob").authType)
    }

    @Test
    fun `cookie lookups never return an oauth session`() = runBlocking {
        val store = YoutubeSessionStore()
        store.storeOauthSession(
            userId = "alice",
            encryptedAccessToken = crypto.encrypt("access-a"),
            encryptedRefreshToken = crypto.encrypt("refresh-a"),
            accessExpiresAt = 1_000L,
            accountLabel = "",
        )

        assertNull(store.connectedEncrypted("alice"))
    }

    @Test
    fun `rotates the access token without touching the refresh token`() = runBlocking {
        val store = YoutubeSessionStore()
        store.storeOauthSession(
            userId = "alice",
            encryptedAccessToken = crypto.encrypt("access-old"),
            encryptedRefreshToken = crypto.encrypt("refresh"),
            accessExpiresAt = 1_000L,
            accountLabel = "",
        )
        store.replaceAccessToken("alice", crypto.encrypt("access-new"), 5_000L)

        val session = store.oauthEncrypted("alice")!!
        assertEquals("access-new", crypto.decrypt(session.accessToken))
        assertEquals("refresh", crypto.decrypt(session.refreshToken))
        assertEquals(5_000L, session.accessExpiresAt)
    }

    @Test
    fun `a needs reconnect session is not returned`() = runBlocking {
        val store = YoutubeSessionStore()
        store.storeOauthSession(
            userId = "alice",
            encryptedAccessToken = crypto.encrypt("access"),
            encryptedRefreshToken = crypto.encrypt("refresh"),
            accessExpiresAt = 1_000L,
            accountLabel = "",
        )
        store.markNeedsReconnect("alice")

        assertNull(store.oauthEncrypted("alice"))
        assertTrue(store.status("alice").status == "needs_reconnect")
    }
}
