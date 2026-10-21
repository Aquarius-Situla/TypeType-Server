package dev.typetype.server.services

import com.grack.nanojson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class YoutubeTvPlayerMetadataTest {
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
