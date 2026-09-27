package dev.typetype.server.services

import dev.typetype.server.models.BlockedItem
import dev.typetype.server.models.BlockedKeywordItem
import dev.typetype.server.models.ChannelPlaylistsResponse
import dev.typetype.server.models.PlaylistResultItem
import dev.typetype.server.models.PublicPlaylistItem
import dev.typetype.server.models.PublicPlaylistResponse
import dev.typetype.server.testVideoItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BlockedContentFiltersTest {
    private val blocked = BlockedContentProfile(
        videos = listOf(BlockedItem("https://youtube.com/watch?v=blocked", blockedAt = 1)),
        channels = listOf(BlockedItem("https://youtube.com/@blocked", "Blocked Channel", null, 1)),
        keywords = listOf(BlockedKeywordItem("spoiler", 1)),
    )

    @Test
    fun `public playlist hides blocked videos channels and keywords`() {
        val response = PublicPlaylistResponse(
            playlist = PublicPlaylistItem("playlist", "Playlist", "url", "", "Owner", 4, "normal"),
            videos = listOf(
                video("https://youtube.com/watch?v=blocked", "Visible"),
                video("https://youtube.com/watch?v=channel", "Visible", "Blocked Channel"),
                video("https://youtube.com/watch?v=keyword", "Major spoiler"),
                video("https://youtube.com/watch?v=allowed", "Allowed"),
            ),
            nextpage = null,
        )

        assertEquals(
            listOf("https://youtube.com/watch?v=allowed"),
            response.filterBlocked(blocked).videos.map { it.url },
        )
    }

    @Test
    fun `channel playlist list hides playlists from blocked uploaders`() {
        val response = ChannelPlaylistsResponse(
            playlists = listOf(
                playlist("blocked", "Blocked Channel"),
                playlist("allowed", "Allowed Channel"),
            ),
            nextpage = null,
        )

        assertEquals(listOf("allowed"), response.filterBlocked(blocked).playlists.map { it.id })
    }

    private fun video(url: String, title: String, uploaderName: String = "Allowed Channel") =
        testVideoItem().copy(
            id = url,
            url = url,
            title = title,
            uploaderName = uploaderName,
            uploaderUrl = "https://youtube.com/@${uploaderName.lowercase().replace(' ', '-')}",
        )

    private fun playlist(id: String, uploaderName: String) = PlaylistResultItem(
        id = id,
        title = id,
        url = "https://youtube.com/playlist?list=$id",
        thumbnailUrl = "",
        uploaderName = uploaderName,
        streamCount = 1,
        playlistType = "normal",
    )
}
