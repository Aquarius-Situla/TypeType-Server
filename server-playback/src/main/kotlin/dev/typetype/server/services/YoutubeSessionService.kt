package dev.typetype.server.services

import dev.typetype.server.models.YoutubeSessionCompleteRequest
import dev.typetype.server.models.YoutubeSessionPairingResponse
import dev.typetype.server.models.YoutubeSessionStatusResponse
import dev.typetype.server.models.YoutubeDeviceStartResponse

class YoutubeSessionService(
    private val crypto: YoutubeSessionCrypto?,
    private val pairingStore: YoutubeSessionPairingStore = YoutubeSessionPairingStore(),
    private val store: YoutubeSessionStore = YoutubeSessionStore(),
    private val bindingClient: TypetypeTokenYoutubeSessionClient? = null,
) {
    val isConfigured: Boolean = crypto != null
    val isDeviceLoginConfigured: Boolean = crypto != null && bindingClient != null

    suspend fun startDeviceLogin(userId: String): YoutubeDeviceStartResponse? {
        val client = bindingClient ?: return null
        val start = client.startDeviceLogin(userId) ?: return null
        return YoutubeDeviceStartResponse(
            userCode = start.userCode,
            verificationUrl = start.verificationUrl,
            expiresAt = start.expiresAt,
            intervalSeconds = start.intervalSeconds,
        )
    }

    suspend fun pollDeviceLogin(userId: String): String {
        val key = crypto ?: return DEVICE_UNAVAILABLE
        val client = bindingClient ?: return DEVICE_UNAVAILABLE
        val poll = client.pollDeviceLogin(userId) ?: return DEVICE_UNAVAILABLE
        if (poll.status != DEVICE_AUTHORIZED) return poll.status
        val tokens = poll.tokens ?: return DEVICE_UNAVAILABLE
        val refreshToken = tokens.refreshToken ?: return DEVICE_INVALID
        store.storeOauthSession(
            userId = userId,
            encryptedAccessToken = key.encrypt(tokens.accessToken),
            encryptedRefreshToken = key.encrypt(refreshToken),
            accessExpiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000,
            accountLabel = "",
        )
        return DEVICE_CONNECTED
    }

    suspend fun cancelDeviceLogin(userId: String) {
        bindingClient?.cancelDeviceLogin(userId)
    }

    suspend fun oauthAccessToken(userId: String): String? {
        val key = crypto ?: return null
        val client = bindingClient ?: return null
        val session = store.oauthEncrypted(userId) ?: return null
        val accessToken = runCatching { key.decrypt(session.accessToken) }.getOrNull() ?: return null
        if (session.accessExpiresAt > System.currentTimeMillis() + EXPIRY_MARGIN_MS) return accessToken
        val refreshToken = runCatching { key.decrypt(session.refreshToken) }.getOrNull() ?: return null
        val refreshed = client.refreshDeviceToken(refreshToken)
        if (refreshed == null) {
            store.markNeedsReconnect(userId)
            return null
        }
        store.replaceAccessToken(
            userId = userId,
            encryptedAccessToken = key.encrypt(refreshed.accessToken),
            accessExpiresAt = System.currentTimeMillis() + refreshed.expiresIn * 1000,
        )
        return refreshed.accessToken
    }

    suspend fun disconnectOauth(userId: String) {
        val key = crypto
        val session = store.oauthEncrypted(userId)
        if (key != null && session != null) {
            val refreshToken = runCatching { key.decrypt(session.refreshToken) }.getOrNull()
            if (refreshToken != null) bindingClient?.revokeDeviceToken(refreshToken)
        }
        store.delete(userId)
    }

    suspend fun importCookies(userId: String, rawCookies: String): YoutubeSessionCompleteResult {
        val key = crypto ?: return YoutubeSessionCompleteResult.Unavailable
        val binding = bindingClient?.importSessionBinding(rawCookies)
            ?: return YoutubeSessionCompleteResult.InvalidCredentials
        val cookies = YoutubeSessionCookieNormalizer.normalize(binding.cookies)
            ?: return YoutubeSessionCompleteResult.InvalidCredentials
        val poToken = binding.poToken.trim()
        if (!validCredentials(cookies, poToken, binding.authUser)) {
            return YoutubeSessionCompleteResult.InvalidCredentials
        }
        store.completeForUser(
            userId = userId,
            encryptedCookies = key.encrypt(cookies),
            encryptedPoToken = key.encrypt(poToken),
            authUser = binding.authUser,
        )
        return YoutubeSessionCompleteResult.Completed
    }

    suspend fun createPairing(userId: String): YoutubeSessionPairingResponse =
        pairingStore.create(userId)

    suspend fun complete(request: YoutubeSessionCompleteRequest): YoutubeSessionCompleteResult {
        val crypto = crypto ?: return YoutubeSessionCompleteResult.Unavailable
        val code = request.code.trim().uppercase()
        val cookies = YoutubeSessionCookieNormalizer.normalize(request.cookies)
            ?: return YoutubeSessionCompleteResult.InvalidCredentials
        val poToken = request.poToken.trim()
        if (code.isBlank() || !validCredentials(cookies, poToken, request.authUser)) {
            return YoutubeSessionCompleteResult.InvalidCredentials
        }
        return store.complete(
            code = code,
            encryptedCookies = crypto.encrypt(cookies),
            encryptedPoToken = crypto.encrypt(poToken),
            authUser = request.authUser,
        )
    }

    suspend fun completeRemote(
        userId: String,
        rawCookies: String,
        rawPoToken: String,
        authUser: Int = 0,
    ): YoutubeSessionCompleteResult {
        val crypto = crypto ?: return YoutubeSessionCompleteResult.Unavailable
        val cookies = YoutubeSessionCookieNormalizer.normalize(rawCookies)
            ?: return YoutubeSessionCompleteResult.InvalidCredentials
        val poToken = rawPoToken.trim()
        if (!validCredentials(cookies, poToken, authUser)) {
            return YoutubeSessionCompleteResult.InvalidCredentials
        }
        store.completeForUser(
            userId = userId,
            encryptedCookies = crypto.encrypt(cookies),
            encryptedPoToken = crypto.encrypt(poToken),
            authUser = authUser,
        )
        return YoutubeSessionCompleteResult.Completed
    }

    suspend fun status(userId: String): YoutubeSessionStatusResponse =
        if (isConfigured) store.status(userId) else YoutubeSessionStatusResponse(YoutubeSessionStatus.Disconnected.value, 0, 0)

    suspend fun delete(userId: String): Boolean = store.delete(userId)

    suspend fun connectedCredentials(userId: String): YoutubeSessionCredentials? {
        val crypto = crypto ?: return null
        val encrypted = store.connectedEncrypted(userId) ?: return null
        val credentials = runCatching {
            YoutubeSessionCredentials(
                userId = userId,
                fingerprint = PublicCacheKey.of(
                    "youtube-session",
                    encrypted.cookies,
                    encrypted.poToken,
                    encrypted.authUser.toString(),
                ),
                cookies = crypto.decrypt(encrypted.cookies),
                poToken = crypto.decrypt(encrypted.poToken),
                authUser = encrypted.authUser,
            )
        }.getOrNull()
        if (credentials == null) store.markNeedsReconnect(userId)
        return credentials
    }

    suspend fun markUsed(userId: String): Unit = store.markUsed(userId)

    suspend fun markNeedsReconnect(userId: String): Unit = store.markNeedsReconnect(userId)

    private fun validCredentials(cookies: String, poToken: String, authUser: Int): Boolean =
        authUser in 0..MAX_AUTH_USER && YoutubeSessionCredentialValidator.isValid(cookies, poToken)

    private companion object {
        const val MAX_AUTH_USER = 99
        const val DEVICE_UNAVAILABLE = "unavailable"
        const val DEVICE_INVALID = "invalid"
        const val DEVICE_AUTHORIZED = "authorized"
        const val DEVICE_CONNECTED = "connected"
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}
