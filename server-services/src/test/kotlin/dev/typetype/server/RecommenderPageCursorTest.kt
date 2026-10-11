package dev.typetype.server

import dev.typetype.server.services.RecommenderPageCursor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommenderPageCursorTest {
    @Test
    fun `a cursor survives encoding`() {
        val cursor = RecommenderPageCursor(variant = 2, offset = 40)
        assertEquals("rec.2.40", cursor.encode())
        assertEquals(cursor, RecommenderPageCursor.decode(cursor.encode()))
    }

    @Test
    fun `anything else is not a recommender cursor`() {
        assertNull(RecommenderPageCursor.decode(null))
        assertNull(RecommenderPageCursor.decode("rec.-1.4"))
        assertNull(RecommenderPageCursor.decode("rec.1"))
        assertNull(RecommenderPageCursor.decode("eyJvZmZzZXQiOjB9"))
        assertTrue(RecommenderPageCursor.isRecommenderCursor("rec.0.20"))
        assertFalse(RecommenderPageCursor.isRecommenderCursor("eyJvZmZzZXQiOjB9"))
        assertFalse(RecommenderPageCursor.isRecommenderCursor(null))
    }
}
