package dev.typetype.server.routes

import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.testStreamResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class StreamRouteHelpersTest {
    @Test
    fun `fills missing authenticated uploader metadata from public extraction`() {
        val authenticated = testStreamResponse().copy(
            id = "abc123",
            title = "YouTube video abc123",
            uploaderName = "",
            uploaderUrl = "https://youtube.com/channel/tv",
            uploaderAvatarUrl = "",
            uploaderSubscriberCount = -1,
        )
        val public = testStreamResponse().copy(
            id = "abc123",
            title = "Public title",
            uploaderName = "Public channel",
            uploaderUrl = "https://youtube.com/@public",
            uploaderAvatarUrl = "https://example.test/avatar.jpg",
            uploaderSubscriberCount = 42,
            uploaderVerified = true,
        )

        val result = ExtractionResult.Success(authenticated).withPublicStreamMetadata(
            ExtractionResult.Success(public),
        ) as ExtractionResult.Success

        assertEquals("Public title", result.data.title)
        assertEquals("Public channel", result.data.uploaderName)
        assertEquals("https://youtube.com/channel/tv", result.data.uploaderUrl)
        assertEquals("https://example.test/avatar.jpg", result.data.uploaderAvatarUrl)
        assertEquals(42, result.data.uploaderSubscriberCount)
        assertEquals(true, result.data.uploaderVerified)
    }

    @Test
    fun `preserves specific authenticated title over public title`() {
        val authenticated = ExtractionResult.Success(testStreamResponse().copy(title = "Authenticated title"))
        val public = ExtractionResult.Success(testStreamResponse().copy(title = "Public title"))

        val result = authenticated.withPublicStreamMetadata(public) as ExtractionResult.Success

        assertEquals("Authenticated title", result.data.title)
    }

    @Test
    fun `leaves authenticated response unchanged when public extraction has no metadata`() {
        val authenticated = ExtractionResult.Success(testStreamResponse().copy(uploaderName = "TV channel"))
        val fallback = ExtractionResult.Failure("not available")

        assertSame(authenticated, authenticated.withPublicStreamMetadata(fallback))
    }
}
