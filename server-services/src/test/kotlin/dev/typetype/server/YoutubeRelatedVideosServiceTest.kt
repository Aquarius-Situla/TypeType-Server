package dev.typetype.server

import com.grack.nanojson.JsonParser
import dev.typetype.server.services.relatedVideosOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YoutubeRelatedVideosServiceTest {
    private fun video(id: String, title: String, channel: String, channelId: String) = """
        {"compactVideoRenderer": {
          "videoId": "$id",
          "title": {"simpleText": "$title"},
          "longBylineText": {"runs": [{"text": "$channel", "navigationEndpoint": {"browseEndpoint": {"browseId": "$channelId", "canonicalBaseUrl": "/channel/$channelId"}}}]},
          "shortBylineText": {"runs": [{"text": "$channel", "navigationEndpoint": {"browseEndpoint": {"browseId": "$channelId", "canonicalBaseUrl": "/channel/$channelId"}}}]},
          "lengthText": {"simpleText": "10:05"},
          "viewCountText": {"simpleText": "1,234 views"},
          "publishedTimeText": {"simpleText": "2 days ago"},
          "thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/vi/$id/hqdefault.jpg", "width": 336, "height": 188}]}
        }}"""

    private fun page(vararg results: String) = JsonParser.`object`().from(
        """{"contents": {"twoColumnWatchNextResults": {"secondaryResults": {"secondaryResults": {"results": [${results.joinToString(",")}]}}}}}""",
    )

    @Test
    fun `the videos next to one are read from its page alone`() {
        val related = relatedVideosOf(
            page(
                video("aaaaaaaaaaa", "First neighbour", "Channel one", "UCone"),
                """{"compactRadioRenderer": {"playlistId": "RDaaaaaaaaaaa"}}""",
                video("bbbbbbbbbbb", "Second neighbour", "Channel two", "UCtwo"),
            ),
            timeAgo = null,
        )
        assertEquals(listOf("First neighbour", "Second neighbour"), related.map { it.title })
        assertEquals(listOf("Channel one", "Channel two"), related.map { it.uploaderName })
        assertTrue(related[0].url.endsWith("watch?v=aaaaaaaaaaa"))
        assertTrue(related[1].uploaderUrl.endsWith("UCtwo"))
        assertEquals(605L, related[0].duration)
    }

    @Test
    fun `a page without the column gives no video`() {
        assertEquals(emptyList<Any>(), relatedVideosOf(JsonParser.`object`().from("""{"contents": {}}"""), timeAgo = null))
    }
}
