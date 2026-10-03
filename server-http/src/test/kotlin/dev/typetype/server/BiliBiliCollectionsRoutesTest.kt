package dev.typetype.server

import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.models.StreamCollectionEpisodeItem
import dev.typetype.server.models.StreamCollectionItem
import dev.typetype.server.models.StreamCollectionSectionItem
import dev.typetype.server.models.StreamPartItem
import dev.typetype.server.routes.streamRoutes
import dev.typetype.server.services.StreamService
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BiliBiliCollectionsRoutesTest {
    private val streamService: StreamService = mockk()

    @Test
    fun `GET BiliBili stream includes ordered collection metadata`() = testApplication {
        val collection = StreamCollectionItem(
            "season-1",
            "Series",
            listOf(
                StreamCollectionSectionItem(
                    "main",
                    "Main",
                    listOf(
                        StreamCollectionEpisodeItem(
                            "BV1first",
                            "P1",
                            "https://www.bilibili.com/video/BV1first",
                            "https://i0.hdslb.com/first.jpg",
                        ),
                        StreamCollectionEpisodeItem(
                            "BV1second",
                            "P2",
                            "https://www.bilibili.com/video/BV1second",
                            "https://i0.hdslb.com/second.jpg",
                        ),
                    ),
                ),
            ),
        )
        coEvery { streamService.getStreamInfo(any()) } returns
            ExtractionResult.Success(testStreamResponse().copy(collections = listOf(collection)))
        installRoutes()

        val response = client.get("/streams/bilibili?url=https://www.bilibili.com/video/BV1first")
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val returnedCollection = root["collections"]!!.jsonArray.single().jsonObject
        val episodes = returnedCollection["sections"]!!.jsonArray.single()
            .jsonObject["episodes"]!!.jsonArray

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("season-1", returnedCollection["id"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("P1", "P2"),
            episodes.map { it.jsonObject["title"]!!.jsonPrimitive.content },
        )
        assertEquals(
            listOf("BV1first", "BV1second"),
            episodes.map { it.jsonObject["videoId"]!!.jsonPrimitive.content },
        )
        assertEquals(
            listOf("https://i0.hdslb.com/first.jpg", "https://i0.hdslb.com/second.jpg"),
            episodes.map { it.jsonObject["thumbnailUrl"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `GET BiliBili stream emits an empty collection list when absent`() = testApplication {
        coEvery { streamService.getStreamInfo(any()) } returns ExtractionResult.Success(testStreamResponse())
        installRoutes()

        val response = client.get("/streams/bilibili?url=https://www.bilibili.com/video/BV1single")
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(root["collections"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `GET BiliBili stream includes multipart items`() = testApplication {
        coEvery { streamService.getStreamInfo(any()) } returns ExtractionResult.Success(
            testStreamResponse().copy(
                parts = listOf(
                    StreamPartItem(1, "First", "https://www.bilibili.com/video/BV1multi?p=1", "", 60),
                    StreamPartItem(2, "Second", "https://www.bilibili.com/video/BV1multi?p=2", "", 90),
                ),
            ),
        )
        installRoutes()

        val response = client.get("/streams/bilibili?url=https://www.bilibili.com/video/BV1multi")
        val parts = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["parts"]!!.jsonArray

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(1, 2), parts.map { it.jsonObject["page"]!!.jsonPrimitive.content.toInt() })
        assertEquals(listOf("First", "Second"), parts.map { it.jsonObject["title"]!!.jsonPrimitive.content })
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installRoutes() {
        application {
            install(ContentNegotiation) {
                json(Json { encodeDefaults = true })
            }
            routing { streamRoutes(streamService) }
        }
    }
}
