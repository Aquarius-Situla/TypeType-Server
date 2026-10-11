package dev.typetype.server.services

import dev.typetype.server.cache.CacheService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.schabi.newpipe.extractor.localization.Localization
import dev.typetype.server.sabr.YoutubeSabrFormat

object SabrAdaptiveInitialization {
    private val localization = Localization("en", "US")

    /**
     * SABR_INITIALIZATION_RANGE=off: the direct range request is not tried and playback starts
     * from the SABR bootstrap, as it does anyway when the range is refused. For an instance
     * whose address the provider refuses that request to (403 on every play), it only spares
     * two refused requests a play.
     */
    private val rangeEnabled: Boolean = System.getenv("SABR_INITIALIZATION_RANGE")?.lowercase() != "off"

    suspend fun fetch(
        holder: SabrSessionHolder,
        format: YoutubeSabrFormat,
        cache: CacheService?,
        timeoutMs: Long = 2_000L,
    ): ByteArray? {
        SabrInitializationData.fetch(holder.key.videoId, format, cache)?.let {
            holder.session.streamState.ingestInitializationData(format, it)
            return it
        }
        val data = fetchRange(holder, format, timeoutMs) ?: return null
        SabrInitializationData.remember(holder.key.videoId, format, data, cache)
        return data
    }

    suspend fun fetchRange(
        holder: SabrSessionHolder,
        format: YoutubeSabrFormat,
        timeoutMs: Long,
        enabled: Boolean = rangeEnabled,
    ): ByteArray? {
        if (!enabled) return null
        val poToken = holder.session.streamState.poToken?.takeIf { it.isNotEmpty() }
            ?: holder.playerContextToken?.streamingPoTokenBytesFor(holder.info)
            ?: return null
        return runCatchingNonCancellation {
            runInterruptible(Dispatchers.IO) {
                holder.withPlayerContext {
                    fetchInitializationData(format, localization, timeoutMs, poToken)
                }
            }
        }.getOrNull()
    }
}
