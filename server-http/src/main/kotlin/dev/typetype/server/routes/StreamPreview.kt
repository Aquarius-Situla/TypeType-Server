package dev.typetype.server.routes

import dev.typetype.server.models.ErrorResponse
import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.services.CachedStreamService
import dev.typetype.server.services.StreamRequestWatch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal class StreamPreview(intent: String?, private val enabled: Boolean) {
    private val asked = intent == INTENT

    val context: CoroutineContext
        get() = if (asked) StreamRequestWatch.NobodyWaiting.mark else EmptyCoroutineContext

    fun switchedOff(): ErrorResponse? =
        if (asked && !enabled) ErrorResponse("Previews are switched off on this instance", CachedStreamService.PACED_CODE) else null

    fun notNow(result: ExtractionResult<*>): ErrorResponse? =
        (result as? ExtractionResult.Failure)
            ?.takeIf { asked && it.code == CachedStreamService.PACED_CODE }
            ?.let { ErrorResponse(it.message, it.code) }

    companion object {
        private const val INTENT = "preview"
        val enabledByDefault: Boolean get() = System.getenv("STREAM_PREVIEWS")?.lowercase() != "off"
    }
}
