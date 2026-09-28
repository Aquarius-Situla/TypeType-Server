package dev.typetype.server.services

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
