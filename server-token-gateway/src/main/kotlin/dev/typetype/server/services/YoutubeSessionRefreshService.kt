package dev.typetype.server.services

class YoutubeSessionRefreshService internal constructor(
    private val crypto: YoutubeSessionCrypto?,
    private val store: YoutubeSessionStore,
    private val client: YoutubeSessionCookieRefreshClient,
) {
    constructor(
        crypto: YoutubeSessionCrypto?,
        store: YoutubeSessionStore = YoutubeSessionStore(),
    ) : this(crypto, store, YoutubeSessionCookieRefreshClient())

    val isConfigured: Boolean = crypto != null

    suspend fun refreshAll(): Int {
        val key = crypto ?: return 0
        var refreshed = 0
        for (userId in store.connectedUserIds()) {
            val credentials = store.connectedEncrypted(userId) ?: continue
            val cookies = runCatching { key.decrypt(credentials.cookies) }.getOrElse { continue }
            when (val result = client.refresh(cookies)) {
                is YoutubeSessionRefreshResult.Refreshed -> {
                    store.replaceCookies(userId, key.encrypt(result.cookies))
                    refreshed += 1
                }
                YoutubeSessionRefreshResult.NeedsReconnect -> store.markNeedsReconnect(userId)
                YoutubeSessionRefreshResult.NotRequired,
                YoutubeSessionRefreshResult.RetryableFailure,
                -> Unit
            }
        }
        return refreshed
    }
}
