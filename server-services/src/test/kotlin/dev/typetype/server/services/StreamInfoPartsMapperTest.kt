package dev.typetype.server.services

import dev.typetype.server.models.StreamPartItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class StreamInfoPartsMapperTest {
    @Test
    fun `maps multipart items with page order and metadata`() {
        val streamInfo = StreamInfo(
            5,
            "BV1example?p=2",
            "https://www.bilibili.com/video/BV1example?p=2",
            "P2 - Second",
        )
        streamInfo.setPartitions(
            listOf(
                StreamInfoItem(
                    5,
                    "https://www.bilibili.com/video/BV1example?p=1",
                    "First",
                    StreamType.VIDEO_STREAM,
                ).apply {
                    duration = 120
                    thumbnailUrl = "http://i0.hdslb.com/cover.jpg"
                },
                StreamInfoItem(
                    5,
                    "https://www.bilibili.com/video/BV1example?p=2",
                    "Second",
                    StreamType.VIDEO_STREAM,
                ).apply {
                    duration = 240
                    thumbnailUrl = "https://i1.hdslb.com/cover.jpg"
                },
            ),
        )

        assertEquals(
            listOf(
                StreamPartItem(
                    page = 1,
                    title = "First",
                    url = "https://www.bilibili.com/video/BV1example?p=1",
                    thumbnailUrl = "http://i0.hdslb.com/cover.jpg",
                    duration = 120,
                ),
                StreamPartItem(
                    page = 2,
                    title = "Second",
                    url = "https://www.bilibili.com/video/BV1example?p=2",
                    thumbnailUrl = "https://i1.hdslb.com/cover.jpg",
                    duration = 240,
                ),
            ),
            streamInfo.toStreamResponse().parts,
        )
    }

    @Test
    fun `maps videos without partitions to an empty list`() {
        val streamInfo = StreamInfo(
            5,
            "BV1single",
            "https://www.bilibili.com/video/BV1single",
            "Single",
        )

        assertEquals(emptyList<StreamPartItem>(), streamInfo.toStreamResponse().parts)
    }
}
