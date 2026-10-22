package dev.typetype.server.services

class SabrProtectedResponseGuard(
    private val maximumResponses: Int = SabrPumpPolicy.MAX_PROTECTED_NO_MEDIA_RESPONSES,
) {
    private var lastResponseNumber: Int? = null
    private var lastGeneration: Long? = null
    private var lastPlayerTimeMs: Long? = null
    private var consecutiveResponses = 0

    fun verify(diagnosticTrace: String, generation: Long, playerTimeMs: Long): Unit {
        val response = diagnosticTrace.substringAfterLast("response n=", missingDelimiterValue = "")
        val responseNumber = response.substringBefore(' ').toIntOrNull() ?: return
        if (responseNumber == lastResponseNumber) return
        val playbackProgressed = lastGeneration?.let { it != generation } == true ||
            lastPlayerTimeMs?.let { generation == lastGeneration && playerTimeMs > it } == true
        lastResponseNumber = responseNumber
        lastGeneration = generation
        lastPlayerTimeMs = playerTimeMs
        val details = response.protectedNoMediaDetails()
        if (details == null) {
            consecutiveResponses = 0
            return
        }
        if (playbackProgressed) consecutiveResponses = 0
        consecutiveResponses++
        if (consecutiveResponses >= maximumResponses) {
            throw SabrProtectedNoMediaException(
                responseNumber = responseNumber,
                responsesObserved = consecutiveResponses,
                details = details,
            )
        }
    }

    private fun String.protectedNoMediaDetails(): SabrProtectedResponseDetails? {
        if (!contains("segments=count=0")) return null
        val protection = PROTECTION.find(this) ?: return null
        val status = protection.groupValues[1].toIntOrNull() ?: return null
        if (status < PROTECTION_BOUNDARY) return null
        return SabrProtectedResponseDetails(
            httpStatus = HTTP_STATUS.find(this)?.groupValues?.get(1)?.toIntOrNull(),
            protectionStatus = status,
            maxRetries = protection.groupValues[2].toIntOrNull(),
            backoffMs = BACKOFF.find(this)?.groupValues?.get(1)?.toLongOrNull(),
            reloadRequested = RELOAD.find(this)?.groupValues?.get(1)?.toBooleanStrictOrNull(),
        )
    }

    private companion object {
        const val PROTECTION_BOUNDARY = 2
        val PROTECTION = Regex("""protection=(\d+)/(\d+)""")
        val HTTP_STATUS = Regex("""http=(\d+)""")
        val BACKOFF = Regex("""backoffMs=(\d+)""")
        val RELOAD = Regex("""reload=(true|false)""", RegexOption.IGNORE_CASE)
    }
}

data class SabrProtectedResponseDetails(
    val httpStatus: Int?,
    val protectionStatus: Int,
    val maxRetries: Int?,
    val backoffMs: Long?,
    val reloadRequested: Boolean?,
)

class SabrProtectedNoMediaException(
    val responseNumber: Int,
    val responsesObserved: Int,
    val details: SabrProtectedResponseDetails,
) : RuntimeException("SABR protected no-media response after $responsesObserved bounded attempts")
