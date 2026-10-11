package dev.typetype.server

import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.models.StreamResponse
import dev.typetype.server.routes.streamRoutes
import dev.typetype.server.services.CachedStreamService
import dev.typetype.server.services.StreamRequestWatch
import dev.typetype.server.services.StreamService
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.coroutineContext

class StreamPreviewRoutesTest {
    @Test
    fun `a preview is asked as a video nobody is waiting for, and may be answered not now`() = testApplication {
        val marked = mutableListOf<Boolean>()
        val paced = object : StreamService {
            override suspend fun getStreamInfo(url: String): ExtractionResult<StreamResponse> {
                val background = coroutineContext[StreamRequestWatch.NobodyWaiting] != null
                marked += background
                return if (background) ExtractionResult.Failure("not now", CachedStreamService.PACED_CODE)
                else ExtractionResult.Success(sabrResponse())
            }
        }
        application {
            install(ContentNegotiation) { json() }
            routing { streamRoutes(paced) }
        }
        val preview = client.get("/streams/youtube/sabr?url=https://youtube.com/watch?v=test&intent=preview")
        assertEquals(HttpStatusCode.Conflict, preview.status)
        assertTrue(preview.bodyAsText().contains(CachedStreamService.PACED_CODE))
        val play = client.get("/streams/youtube/sabr?url=https://youtube.com/watch?v=test")
        assertEquals(HttpStatusCode.OK, play.status)
        assertEquals(listOf(true, false), marked)
    }

    @Test
    fun `with previews switched off the provider is asked only for a play`() = testApplication {
        var asked = 0
        val counting = object : StreamService {
            override suspend fun getStreamInfo(url: String): ExtractionResult<StreamResponse> {
                asked += 1
                return ExtractionResult.Success(sabrResponse())
            }
        }
        application {
            install(ContentNegotiation) { json() }
            routing { streamRoutes(counting, previewsEnabled = false) }
        }
        val preview = client.get("/streams/youtube/sabr?url=https://youtube.com/watch?v=test&intent=preview")
        assertEquals(HttpStatusCode.Conflict, preview.status)
        assertEquals(0, asked)
        assertEquals(HttpStatusCode.OK, client.get("/streams/youtube/sabr?url=https://youtube.com/watch?v=test").status)
        assertEquals(1, asked)
    }

    private fun sabrResponse() = testStreamResponse(
        videoOnlyStreams = listOf(testVideoStream().copy(deliveryMethod = "sabr")),
        audioStreams = listOf(testAudioStream(deliveryMethod = "sabr")),
    )
}
