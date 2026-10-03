package dev.typetype.server.services

import dev.typetype.server.models.StreamCollectionEpisodeItem
import dev.typetype.server.models.StreamCollectionItem
import dev.typetype.server.models.StreamCollectionSectionItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.stream.StreamCollectionInfo
import org.schabi.newpipe.extractor.stream.StreamInfo

class StreamInfoCollectionsMapperTest {
    @Test
    fun `maps collection sections and episodes in extractor order`() {
        val streamInfo = StreamInfo(
            6,
            "BV1example",
            "https://www.bilibili.com/video/BV1example",
            "P1 - Opening",
        )
        streamInfo.setCollections(
            listOf(
                StreamCollectionInfo(
                    "collection-1",
                    "Series",
                    listOf(
                        StreamCollectionInfo.Section(
                            "section-main",
                            "Main",
                            listOf(
                                StreamCollectionInfo.Episode(
                                    "BV1episode1", "P1", "https://www.bilibili.com/video/BV1episode1", 101,
                                    "https://i0.hdslb.com/episode1.jpg",
                                ),
                                StreamCollectionInfo.Episode(
                                    "BV1episode2", "P2", "https://www.bilibili.com/video/BV1episode2", 102,
                                ),
                            ),
                        ),
                        StreamCollectionInfo.Section(
                            "section-extra",
                            "Extras",
                            listOf(
                                StreamCollectionInfo.Episode(
                                    "BV1episode3", "P3", "https://www.bilibili.com/video/BV1episode3", 103,
                                ),
                            ),
                        ),
                    ),
                ),
                StreamCollectionInfo(
                    "collection-2",
                    "Bonus",
                    listOf(
                        StreamCollectionInfo.Section(
                            "section-bonus",
                            "Bonus episodes",
                            emptyList(),
                        ),
                    ),
                ),
            ),
        )

        val response = streamInfo.toStreamResponse()

        assertEquals("P1 - Opening", response.title)
        assertEquals(
            listOf(
                StreamCollectionItem(
                    "collection-1",
                    "Series",
                    listOf(
                        StreamCollectionSectionItem(
                            "section-main",
                            "Main",
                            listOf(
                                StreamCollectionEpisodeItem(
                                    "BV1episode1",
                                    "P1",
                                    "https://www.bilibili.com/video/BV1episode1",
                                    "https://i0.hdslb.com/episode1.jpg",
                                ),
                                StreamCollectionEpisodeItem("BV1episode2", "P2", "https://www.bilibili.com/video/BV1episode2"),
                            ),
                        ),
                        StreamCollectionSectionItem(
                            "section-extra",
                            "Extras",
                            listOf(
                                StreamCollectionEpisodeItem("BV1episode3", "P3", "https://www.bilibili.com/video/BV1episode3"),
                            ),
                        ),
                    ),
                ),
                StreamCollectionItem(
                    "collection-2",
                    "Bonus",
                    listOf(StreamCollectionSectionItem("section-bonus", "Bonus episodes", emptyList())),
                ),
            ),
            response.collections,
        )
    }

    @Test
    fun `maps videos without collections to an empty list`() {
        val streamInfo = StreamInfo(6, "BV1single", "https://www.bilibili.com/video/BV1single", "Single")

        assertEquals(emptyList<StreamCollectionItem>(), streamInfo.toStreamResponse().collections)
    }
}
