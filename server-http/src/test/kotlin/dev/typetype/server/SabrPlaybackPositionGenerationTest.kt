package dev.typetype.server

import dev.typetype.server.routes.SabrPlaybackWindowHandler
import dev.typetype.server.services.SabrSessionHolder
import dev.typetype.server.services.SabrSessionKey
import dev.typetype.server.services.SabrSessionStore
import dev.typetype.server.sabr.YoutubeSabrFormat
import dev.typetype.server.sabr.YoutubeSabrInfo
import dev.typetype.server.sabr.YoutubeSabrSession
import dev.typetype.server.sabr.YoutubeSabrStreamState
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SabrPlaybackPositionGenerationTest {
    @Test
    fun `stale position does not rewind session and returns active generation`() = testApplication {
        val store = mockk<SabrSessionStore>(relaxed = true)
        val streamState = mockk<YoutubeSabrStreamState>(relaxed = true)
        val session = mockk<YoutubeSabrSession>()
        every { session.streamState } returns streamState
        val holder = SabrSessionHolder(
            session = session,
            info = mockk<YoutubeSabrInfo>(),
            audioFormat = format(140),
            videoFormat = format(136),
            sessionToken = "session-token",
            key = SabrSessionKey("video", "user", 140, null, 136, 0L),
            lastRequestAt = Instant.EPOCH,
        )
        holder.advancePlaybackGeneration(30_000L)
        every { store.lookupByToken("session-token") } returns holder
        application {
            install(ContentNegotiation) { json() }
            val handler = SabrPlaybackWindowHandler(store)
            routing {
                post("/sabr/playback/{sessionId}/position") {
                    handler.position(call, call.parameters["sessionId"].orEmpty())
                }
            }
        }

        val response = client.post("/sabr/playback/session-token/position") {
            contentType(ContentType.Application.Json)
            setBody("""{"generation":0,"playerTimeMs":10000,"videoItag":136,"audioItag":140,"playbackRate":2.0}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"generation\":1"))
        assertTrue(body.contains("\"playerTimeMs\":30000"))
        assertEquals(30_000L, holder.playerTimeMs())
        verify(exactly = 0) { streamState.setPlaybackRate(any()) }
    }

    private fun format(itag: Int): YoutubeSabrFormat {
        val format = mockk<YoutubeSabrFormat>(relaxed = true)
        every { format.itag } returns itag
        every { format.audioTrackId } returns null
        return format
    }
}
