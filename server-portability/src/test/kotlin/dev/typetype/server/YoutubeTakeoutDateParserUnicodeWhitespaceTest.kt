package dev.typetype.server

import dev.typetype.server.services.YoutubeTakeoutDateParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class YoutubeTakeoutDateParserUnicodeWhitespaceTest {
    @Test
    fun `parses narrow no-break space before AM or PM`() {
        assertEquals(
            1_789_574_528_000L,
            YoutubeTakeoutDateParser.parseEpochMillis("Sep 16, 2026, 6:02:08\u202fPM CEST"),
        )
    }
}
