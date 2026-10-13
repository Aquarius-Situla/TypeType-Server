package dev.typetype.server.services

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DeArrowClientTest {
    @Test
    fun `treats upstream not-found as no branding`() {
        val client = DeArrowClient(clientReturning(404))

        assertNull(runBlocking { client.branding(VIDEO_ID) })
    }

    @Test
    fun `reports upstream server errors as unavailable`() {
        val client = DeArrowClient(clientReturning(503))

        assertThrows(DeArrowUnavailableException::class.java) {
            runBlocking { client.branding(VIDEO_ID) }
        }
    }

    @Test
    fun `wraps branding network errors without losing the cause`() {
        val cause = IOException("network unavailable")
        val client = DeArrowClient(OkHttpClient.Builder().addInterceptor { throw cause }.build())

        val exception = assertThrows(DeArrowUnavailableException::class.java) {
            runBlocking { client.branding(VIDEO_ID) }
        }

        assertTrue(exception.hasCause(cause))
    }

    @Test
    fun `does not swallow coroutine cancellation`() {
        val cancellation = CancellationException("cancelled")
        val client = DeArrowClient(OkHttpClient.Builder().addInterceptor { throw cancellation }.build())

        val exception = assertThrows(CancellationException::class.java) {
            runBlocking { client.branding(VIDEO_ID) }
        }

    }

    private fun Throwable.hasCause(expected: Throwable): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current === expected) return true
            current = current.cause
        }
        return false
    }

    private fun clientReturning(status: Int): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("upstream")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private companion object {
        const val VIDEO_ID = "stZ3ZoR_8eg"
    }
}
