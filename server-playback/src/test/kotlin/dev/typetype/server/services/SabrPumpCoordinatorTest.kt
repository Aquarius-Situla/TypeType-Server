package dev.typetype.server.services

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SabrProtectedResponseGuardTest {
    @Test
    fun `bounded protection responses become terminal`() {
        val guard = SabrProtectedResponseGuard(maximumResponses = 5)

        repeat(4) { index ->
            assertDoesNotThrow { guard.verify(response(index + 1, protection = 3, segments = 0), 0, 0) }
        }

        assertThrows(SabrProtectedNoMediaException::class.java) {
            guard.verify(response(5, protection = 3, segments = 0), 0, 0)
        }
    }

    @Test
    fun `media progress resets protection response budget`() {
        val guard = SabrProtectedResponseGuard(maximumResponses = 3)

        guard.verify(response(1, protection = 3, segments = 0), 0, 0)
        guard.verify(response(2, protection = 3, segments = 0), 0, 0)
        guard.verify(response(3, protection = 1, segments = 1), 0, 0)
        guard.verify(response(4, protection = 3, segments = 0), 0, 0)
        assertDoesNotThrow { guard.verify(response(5, protection = 3, segments = 0), 0, 0) }
    }

    @Test
    fun `advancing playback resets protection response budget`() {
        val guard = SabrProtectedResponseGuard(maximumResponses = 3)

        guard.verify(response(1, protection = 3, segments = 0), 0, 53_000)
        guard.verify(response(2, protection = 3, segments = 0), 0, 53_000)
        guard.verify(response(3, protection = 3, segments = 0), 0, 55_000)
        assertDoesNotThrow {
            guard.verify(response(4, protection = 3, segments = 0), 0, 55_000)
        }
        assertThrows(SabrProtectedNoMediaException::class.java) {
            guard.verify(response(5, protection = 3, segments = 0), 0, 55_000)
        }
    }

    @Test
    fun `new playback generation resets protection response budget`() {
        val guard = SabrProtectedResponseGuard(maximumResponses = 3)

        guard.verify(response(1, protection = 3, segments = 0), 0, 53_000)
        guard.verify(response(2, protection = 3, segments = 0), 0, 53_000)
        guard.verify(response(3, protection = 3, segments = 0), 1, 370_000)
        assertDoesNotThrow {
            guard.verify(response(4, protection = 3, segments = 0), 1, 370_000)
        }
        assertThrows(SabrProtectedNoMediaException::class.java) {
            guard.verify(response(5, protection = 3, segments = 0), 1, 370_000)
        }
    }

    @Test
    fun `same response is counted once`() {
        val guard = SabrProtectedResponseGuard(maximumResponses = 2)
        val response = response(8, protection = 3, segments = 0)

        guard.verify(response, 0, 0)
        assertDoesNotThrow { guard.verify(response, 0, 0) }
    }

    @Test
    fun `terminal failure retains safe response diagnostics only`() {
        val guard = SabrProtectedResponseGuard(maximumResponses = 1)

        val failure = assertThrows(SabrProtectedNoMediaException::class.java) {
            guard.verify(
                "request n=14 | response n=14 http=403 segments=count=0 " +
                    "decoded={protection=3/20, backoffMs=1500, reload=true, rawValue=must-not-leak}",
                generation = 0,
                playerTimeMs = 0,
            )
        }

        assertEquals(14, failure.responseNumber)
        assertEquals(1, failure.responsesObserved)
        assertEquals(403, failure.details.httpStatus)
        assertEquals(3, failure.details.protectionStatus)
        assertEquals(20, failure.details.maxRetries)
        assertEquals(1500L, failure.details.backoffMs)
        assertEquals(true, failure.details.reloadRequested)
        assertFalse(failure.message.orEmpty().contains("must-not-leak"))
    }

    private fun response(number: Int, protection: Int, segments: Int): String =
        "request n=$number | response n=$number http=200 segments=count=$segments " +
            "decoded={protection=$protection/20, backoffMs=2000}"
}
