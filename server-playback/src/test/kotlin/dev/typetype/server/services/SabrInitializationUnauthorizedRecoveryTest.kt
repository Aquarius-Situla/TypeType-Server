package dev.typetype.server.services

import dev.typetype.server.sabr.SabrMediaSegment
import dev.typetype.server.sabr.SabrProtocolException
import dev.typetype.server.sabr.SabrSegmentRequest
import dev.typetype.server.sabr.YoutubeSabrFormat
import dev.typetype.server.sabr.YoutubeSabrInfo
import dev.typetype.server.sabr.YoutubeSabrSession
import dev.typetype.server.sabr.YoutubeSabrStreamState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.localization.Localization
import java.time.Instant

class SabrInitializationUnauthorizedRecoveryTest {
    @Test
    fun `refreshes the video token and retries initialization after upstream 403`() = runTest {
        val (holder, session, streamState, request) = fixture(httpStatus = 403)
        val expected = mockk<SabrMediaSegment>()
        val refreshed = tokenBundle(byteArrayOf(2))
        every { session.fetchSegment(request, any()) } throws SabrProtocolException("No init segment") andThen expected

        val result = fetchSabrInitializationSegment(
            holder,
            request,
            Localization("en", "US"),
            segmentCache = null,
            markServed = false,
            unauthorizedRecovery = SabrUnauthorizedResponseRecovery { refreshed },
        )

        assertSame(expected, result)
        verify(exactly = 1) { streamState.setPoToken(match { it.contentEquals(byteArrayOf(2)) }) }
        verify(exactly = 2) { session.fetchSegment(request, any()) }
    }

    @Test
    fun `does not retry initialization for non-unauthorized status`() = runTest {
        val (holder, session, _, request) = fixture(httpStatus = 500)
        var refreshes = 0
        every { session.fetchSegment(request, any()) } throws SabrProtocolException("No init segment")

        val result = fetchSabrInitializationSegment(
            holder,
            request,
            Localization("en", "US"),
            segmentCache = null,
            markServed = false,
            unauthorizedRecovery = SabrUnauthorizedResponseRecovery {
                refreshes++
                tokenBundle(byteArrayOf(2))
            },
        )

        assertNull(result)
        assertEquals(0, refreshes)
        verify(exactly = 1) { session.fetchSegment(request, any()) }
    }

    private fun fixture(
        httpStatus: Int,
    ): InitializationFixture {
        val format = mockk<YoutubeSabrFormat>()
        every { format.itag } returns 137
        val request = mockk<SabrSegmentRequest>()
        every { request.format } returns format
        val info = mockk<YoutubeSabrInfo>()
        every { info.videoId } returns VIDEO_ID
        every { info.visitorData } returns VISITOR_DATA
        val streamState = mockk<YoutubeSabrStreamState>(relaxed = true)
        every { streamState.poToken } returns byteArrayOf(1)
        val session = mockk<YoutubeSabrSession>()
        every { session.streamState } returns streamState
        every { session.requestNumber } returnsMany listOf(1, 2)
        every { session.diagnosticTrace } returns "response n=1 http=$httpStatus segments=count=0"
        every { session.getCachedSegment(request) } returns null
        every { session.isBeyondEnd(request) } returns false
        every { session.prepareForInitialization(format) } returns Unit
        every { session.addDiagnosticEvent(any()) } returns Unit
        val holder = SabrSessionHolder(
            session = session,
            info = info,
            audioFormat = format,
            videoFormat = format,
            sessionToken = "session",
            key = SabrSessionKey(VIDEO_ID, "user", 140, null, 137, 0L),
            lastRequestAt = Instant.EPOCH,
            playerContextToken = tokenBundle(byteArrayOf(1)),
        )
        return InitializationFixture(holder, session, streamState, request)
    }

    private fun tokenBundle(videoToken: ByteArray) = SabrTokenBundle(
        videoId = VIDEO_ID,
        visitorBoundPoToken = "visitor-token",
        visitorBoundPoTokenBytes = byteArrayOf(3),
        visitorData = VISITOR_DATA,
        videoBoundPoToken = "video-token",
        videoBoundPoTokenBytes = videoToken,
    )

    private data class InitializationFixture(
        val holder: SabrSessionHolder,
        val session: YoutubeSabrSession,
        val streamState: YoutubeSabrStreamState,
        val request: SabrSegmentRequest,
    )

    private companion object {
        const val VIDEO_ID = "video-id"
        const val VISITOR_DATA = "visitor-data"
    }
}
