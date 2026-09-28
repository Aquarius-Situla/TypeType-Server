package dev.typetype.server.services

import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.UnknownHostException

class UnifiedPushSender(
    private val endpointValidator: UnifiedPushEndpointValidator = UnifiedPushEndpointValidator(),
    client: OkHttpClient = OkHttpClient(),
    private val payloadEncryptor: WebPushPayloadEncryptor = WebPushPayloadEncryptor(),
) : PushEndpointSender {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun send(
        endpoint: String,
        eventId: String,
        payload: String,
        p256dh: String,
        auth: String,
    ): PushSendResult = withContext(Dispatchers.IO) {
        val validated = endpointValidator.validate(endpoint)
        if (validated !is EndpointValidationResult.Valid) return@withContext PushSendResult.InvalidEndpoint
        val encryptedPayload = try {
            payloadEncryptor.encrypt(payload, p256dh, auth)
        } catch (_: InvalidWebPushSubscription) {
            return@withContext PushSendResult.InvalidSubscription
        } catch (_: GeneralSecurityException) {
            return@withContext PushSendResult.Retry(null)
        }
        val request = Request.Builder()
            .url(validated.uri.toString())
            .header("Content-Type", BINARY_MEDIA_TYPE.toString())
            .header("Content-Encoding", "aes128gcm")
            .header("TTL", MESSAGE_TTL_SECONDS.toString())
            .header("X-TypeType-Event-Id", eventId)
            .post(encryptedPayload.toRequestBody(BINARY_MEDIA_TYPE))
            .build()
        val requestClient = client.newBuilder()
            .dns(StaticPushDns(validated.uri.host.orEmpty(), validated.addresses))
            .build()
        runCatching { requestClient.newCall(request).execute().use { response -> response.code } }
            .fold(
                onSuccess = { code ->
                    when {
                        code in 200..299 -> PushSendResult.Delivered
                        code == 404 || code == 410 -> PushSendResult.InvalidEndpoint
                        else -> PushSendResult.Retry(code)
                    }
                },
                onFailure = { PushSendResult.Retry(null) },
            )
    }

    private class StaticPushDns(
        private val validatedHost: String,
        private val addresses: List<InetAddress>,
    ) : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname.equals(validatedHost, ignoreCase = true)) addresses
            else throw UnknownHostException("Unexpected push endpoint host")
    }

    private companion object {
        const val MESSAGE_TTL_SECONDS = 86400
        val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}

interface PushEndpointSender {
    suspend fun send(endpoint: String, eventId: String, payload: String, p256dh: String, auth: String): PushSendResult
}

sealed interface PushSendResult {
    data object Delivered : PushSendResult
    data object InvalidEndpoint : PushSendResult
    data object InvalidSubscription : PushSendResult
    data class Retry(val statusCode: Int?) : PushSendResult
}
