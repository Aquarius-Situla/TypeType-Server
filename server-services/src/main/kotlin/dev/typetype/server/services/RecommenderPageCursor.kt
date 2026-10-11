package dev.typetype.server.services

private const val PREFIX = "rec"
private const val SEPARATOR = "."

data class RecommenderPageCursor(val variant: Int, val offset: Int) {
    fun encode(): String = listOf(PREFIX, variant, offset).joinToString(SEPARATOR)

    companion object {
        fun isRecommenderCursor(raw: String?): Boolean = raw?.startsWith(PREFIX + SEPARATOR) == true

        fun decode(raw: String?): RecommenderPageCursor? {
            val parts = raw?.split(SEPARATOR) ?: return null
            if (parts.size != 3 || parts[0] != PREFIX) return null
            val variant = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val offset = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return RecommenderPageCursor(variant, offset)
        }
    }
}
