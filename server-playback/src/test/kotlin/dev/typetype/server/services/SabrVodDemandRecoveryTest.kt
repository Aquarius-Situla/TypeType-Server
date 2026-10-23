package dev.typetype.server.services

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.typetype.server.sabr.SabrSegmentRequest
import dev.typetype.server.sabr.YoutubeSabrFormat
import dev.typetype.server.sabr.YoutubeSabrInfo
import dev.typetype.server.sabr.YoutubeSabrSession
import dev.typetype.server.sabr.YoutubeSabrStreamState
import java.time.Instant

class SabrVodDemandRecoveryTest {
    @Test
    fun `readvertised near-edge demand stays targeted on the next vod round`() = runTest {
        SabrSegmentDemandTracker.clearAll()
        try {
            val audio = format(140, isAudio = true)
            val video = format(137, isAudio = false)
            val request = SabrSegmentRequest.media(video, 5)
            val session = mockk<YoutubeSabrSession>(relaxed = true)
            val state = mockk<YoutubeSabrStreamState>(relaxed = true)
            every { session.streamState } returns state
            every { session.requestNumber } returns 1
            every { session.getCachedSegment(any()) } returns null
            every { session.isBeyondEnd(any()) } returns false
            every { state.getMinBufferedEndMs() } returns 22_188L
            every { state.getSegmentStartMs(any(), any()) } answers {
                val format = firstArg<YoutubeSabrFormat>()
                val sequence = secondArg<Int>()
                if (format.itag == video.itag && sequence == 5) 23_533L else sequence * 5_000L
            }
            every { state.getSegmentEndMs(any(), any()) } answers {
                val format = firstArg<YoutubeSabrFormat>()
                val sequence = secondArg<Int>()
                val start = if (format.itag == video.itag && sequence == 5) 23_533L else sequence * 5_000L
                start + 5_000L
            }
            every { state.getSegmentNumberAtOrAfterTimeMs(any(), any()) } answers {
                if (firstArg<YoutubeSabrFormat>().itag == video.itag) 5 else 4
            }
            every { state.getEndSegment(any()) } returns 1_000L
            val firstResponse = response(requestPerformed = true)
            val secondResponse = response(requestPerformed = false)
            var requests = 0
            every { session.pumpOnceStreamingForDemand(any(), request) } answers {
                if (requests++ == 0) firstResponse else secondResponse
            }
            val holder = SabrSessionHolder(
                session = session,
                info = mockk<YoutubeSabrInfo>(),
                audioFormat = audio,
                videoFormat = video,
                sessionToken = "vod-session",
                key = SabrSessionKey("video", "user", audio.itag, null, video.itag, 0L),
                lastRequestAt = Instant.EPOCH,
            )
            holder.requestSegmentDemand(request, registeredAtMs = 0L)
            assertEquals("137:5", holder.pendingSegmentDemandSummary())
            val identity = requireNotNull(holder.segmentDemandIdentity(request))
            val runtime = SabrPumpRuntime()
            var rounds = 0

            SabrSessionPumpLoop(runtimeFactory = { runtime }).run({ rounds++ < 2 }, holder, intervalMs = 0L)

            verify(exactly = 2) { session.pumpOnceStreamingForDemand(any(), request) }
            verify(exactly = 1) { session.prepareForMissingSegment(request) }
            verifyOrder {
                state.setPlayerTimeMs(6_188L)
                state.setPlayerTimeMs(24_533L)
            }
            assertTrue(runtime.demandNeedsTargetedShape(identity))
        } finally {
            SabrSegmentDemandTracker.clearAll()
        }
    }

    private fun format(itag: Int, isAudio: Boolean): YoutubeSabrFormat = mockk {
        every { this@mockk.itag } returns itag
        every { this@mockk.isAudio } returns isAudio
        every { this@mockk.bitrate } returns if (isAudio) 128_000 else 2_000_000
        every { this@mockk.lastModified } returns 0L
        every { this@mockk.xtags } returns ""
    }

    private fun response(requestPerformed: Boolean): YoutubeSabrSession.DemandResponseResult {
        val result = mockk<YoutubeSabrSession.DemandResponseResult>()
        every { result.segmentCount } returns 0
        every { result.requestPerformed } returns requestPerformed
        return result
    }
}
