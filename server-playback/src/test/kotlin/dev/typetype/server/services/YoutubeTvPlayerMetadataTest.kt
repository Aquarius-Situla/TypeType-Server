package dev.typetype.server.services

import com.grack.nanojson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class YoutubeTvPlayerMetadataTest {
    @Test
    fun `prefers the canonical video title`() {
        val response = JsonParser.`object`().from(
            """{"videoDetails":{"title":"Canonical title"},"microformat":{"playerMicroformatRenderer":{"title":{"simpleText":"Displayed title"}}}}""",
        )

        assertEquals("Canonical title", response.youtubeTvTitle())
    }

    @Test
    fun `uses the player microformat when TV omits video title`() {
        val response = JsonParser.`object`().from(
            """{"videoDetails":{"title":""},"microformat":{"playerMicroformatRenderer":{"title":{"simpleText":"TV title"}}}}""",
        )

        assertEquals("TV title", response.youtubeTvTitle())
    }

    @Test
    fun `uses the player microformat channel id when TV omits video channel id`() {
        val response = JsonParser.`object`().from(
            """{"videoDetails":{"channelId":""},"microformat":{"playerMicroformatRenderer":{"externalChannelId":"UCchannel"}}}""",
        )

        assertEquals("UCchannel", response.youtubeTvChannelId())
    }

    @Test
    fun `prefers the canonical channel id`() {
        val response = JsonParser.`object`().from(
            """{"videoDetails":{"channelId":"UCcanonical"},"microformat":{"playerMicroformatRenderer":{"externalChannelId":"UCdisplayed"}}}""",
        )

        assertEquals("UCcanonical", response.youtubeTvChannelId())
    }

    @Test
    fun `prefers the canonical video author`() {
        val response = JsonParser.`object`().from(
            """{"videoDetails":{"author":"Canonical channel"},"microformat":{"playerMicroformatRenderer":{"ownerChannelName":"Displayed owner"}}}""",
        )

        assertEquals("Canonical channel", response.youtubeTvUploaderName())
    }

    @Test
    fun `uses the player microformat when TV omits video author`() {
        val response = JsonParser.`object`().from(
            """{"videoDetails":{"author":""},"microformat":{"playerMicroformatRenderer":{"ownerChannelName":"TV channel"}}}""",
        )

        assertEquals("TV channel", response.youtubeTvUploaderName())
    }
}
