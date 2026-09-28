package dev.typetype.server

import dev.typetype.server.services.UnifiedPushEndpointValidator
import dev.typetype.server.services.UnifiedPushSender
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class UnifiedPushSenderTest {
    @Test
    fun sendUsesEncryptedWebPushHeadersAndBody() = kotlinx.coroutines.test.runTest {
        val captured = AtomicReference<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured.set(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(202)
                .message("Accepted")
                .body(ByteArray(0).toResponseBody())
                .build()
        }.build()
        val validator = UnifiedPushEndpointValidator {
            arrayOf(InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))
        }
        val result = UnifiedPushSender(validator, client).send(
            "https://push.example/subscription",
            "event-123",
            "{\"eventType\":\"subscription_new_video\"}",
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
            "BTBZMqHH6r4Tts7J_aSIgg",
        )

        assertEquals(dev.typetype.server.services.PushSendResult.Delivered, result)
        val request = requireNotNull(captured.get())
        assertEquals("aes128gcm", request.header("Content-Encoding"))
        assertEquals("application/octet-stream", request.header("Content-Type"))
        assertEquals("86400", request.header("TTL"))
        assertEquals("event-123", request.header("X-TypeType-Event-Id"))
        assertEquals(null, request.header("Authorization"))
        val body = Buffer()
        requireNotNull(request.body).writeTo(body)
        assertFalse(body.readByteArray().contentEquals("{\"eventType\":\"subscription_new_video\"}".toByteArray()))
    }
}
