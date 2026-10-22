package dev.typetype.server

import dev.typetype.server.services.YoutubeRemoteBrowserConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class YoutubeRemoteBrowserConfigTest {
    @Test
    fun `defaults callback to Docker Server service name`() {
        assertEquals(
            "http://typetype-server:8080",
            YoutubeRemoteBrowserConfig.callbackBaseUrl { null },
        )
    }

    @Test
    fun `uses configured callback base URL`() {
        assertEquals(
            "http://custom-server:28080",
            YoutubeRemoteBrowserConfig.callbackBaseUrl { " http://custom-server:28080 " },
        )
    }
}
