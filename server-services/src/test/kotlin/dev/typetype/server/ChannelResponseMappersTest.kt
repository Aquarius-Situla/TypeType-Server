package dev.typetype.server

import dev.typetype.server.services.toChannelTabResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.channel.ChannelTabInfo
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException

class ChannelResponseMappersTest {

    @Test
    fun `empty channel tab with extraction errors is not treated as success`() {
        val cause = ParsingException("temporary upstream denial")
        val info = mockk<ChannelTabInfo>()
        every { info.relatedItems } returns emptyList()
        every { info.errors } returns listOf(cause)

        val error = assertThrows(ExtractionException::class.java) { info.toChannelTabResponse() }

        assertSame(cause, error.cause)
    }

    @Test
    fun `genuinely empty channel tab remains a valid response`() {
        val info = mockk<ChannelTabInfo>()
        every { info.relatedItems } returns emptyList()
        every { info.errors } returns emptyList()
        every { info.name } returns "Empty channel"
        every { info.nextPage } returns null

        val response = info.toChannelTabResponse()

        assertEquals("Empty channel", response.name)
        assertEquals(0, response.videos.size)
    }
}
