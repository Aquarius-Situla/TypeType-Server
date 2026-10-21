package dev.typetype.server.services

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import dev.typetype.server.sabr.YoutubeSabrClientProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class YoutubeTvOauthPlayerRequestBodyTest {
    @Test
    fun `TV player and SABR profiles use the same user agent`() {
        assertEquals(YOUTUBE_TV_USER_AGENT, YoutubeSabrClientProfile.TVHTML5.userAgent)
    }

    @Test
    fun `includes TV PO token and living room id`() {
        val request = playerRequest(poToken = "tv-session-token")
        val client = request.getObject("context").getObject("client")

        assertEquals("tv-session-token", request.getObject("serviceIntegrityDimensions").getString("poToken"))
        assertEquals("living-room-id", client.getObject("tvAppInfo").getString("livingRoomPoTokenId"))
        assertFalse(request.containsKey("tvAppInfo"))
        assertEquals("visitor-data", client.getString("visitorData"))
    }

    @Test
    fun `omits service integrity dimensions when the token is unavailable`() {
        val request = playerRequest(poToken = null)

        assertFalse(request.containsKey("serviceIntegrityDimensions"))
    }

    private fun playerRequest(poToken: String?): JsonObject = JsonParser.`object`().from(
        buildYoutubeTvOauthPlayerBody(
            clientName = "TVHTML5",
            videoId = "video-id",
            clientVersion = "7.20261014.18.00",
            visitorData = "visitor-data",
            livingRoomPoTokenId = "living-room-id",
            poToken = poToken,
            cpn = "playback-nonce",
            signatureTimestamp = 123_456_789L,
        ),
    )
}
