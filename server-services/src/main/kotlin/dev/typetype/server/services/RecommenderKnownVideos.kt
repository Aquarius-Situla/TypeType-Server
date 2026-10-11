package dev.typetype.server.services

import dev.typetype.server.db.DatabaseFactory
import dev.typetype.server.db.tables.HistoryTable
import dev.typetype.server.models.VideoItem
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll

object RecommenderKnownVideos {
    suspend fun find(userId: String, urls: List<String>): List<VideoItem> {
        if (urls.isEmpty()) return emptyList()
        val found = DatabaseFactory.query {
            HistoryTable.selectAll()
                .where { (HistoryTable.userId eq userId) and (HistoryTable.url inList urls) }
                .associate { row ->
                    row[HistoryTable.url] to VideoItem(
                        id = YoutubeTypeTypeMapper.videoId(row[HistoryTable.url]).orEmpty(),
                        title = row[HistoryTable.title],
                        url = row[HistoryTable.url],
                        thumbnailUrl = row[HistoryTable.thumbnail],
                        uploaderName = row[HistoryTable.channelName],
                        uploaderUrl = row[HistoryTable.channelUrl],
                        uploaderAvatarUrl = row[HistoryTable.channelAvatar],
                        duration = row[HistoryTable.duration],
                        viewCount = -1L,
                        uploadDate = "",
                        streamType = "video_stream",
                        isShortFormContent = row[HistoryTable.url].isShortsUrl(),
                        uploaderVerified = false,
                        shortDescription = null,
                    )
                }
        }
        return urls.mapNotNull(found::get)
    }
}
