package dev.typetype.server.services

import dev.typetype.server.cache.CacheJson
import dev.typetype.server.cache.CacheService
import dev.typetype.server.models.ExtractionResult
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

object PublicExtractionCache {
    private val logger = LoggerFactory.getLogger(PublicExtractionCache::class.java)
    private val flights = SingleFlight<ExtractionResult<*>>()

    suspend fun <T> getOrLoad(
        cache: CacheService,
        area: String,
        key: String,
        serializer: KSerializer<T>,
        ttlSeconds: (T) -> Long,
        load: suspend () -> ExtractionResult<T>,
    ): ExtractionResult<T> {
        cached(cache, area, key, serializer)?.let { return ExtractionResult.Success(it) }
        @Suppress("UNCHECKED_CAST")
        return flights.run(key) {
            cached(cache, area, key, serializer)?.let { return@run ExtractionResult.Success(it) }
            logger.info("cache event=miss area={} key={}", area, key)
            val result = load()
            if (result is ExtractionResult.Success) {
                store(cache, area, key, serializer, result.data, ttlSeconds(result.data))
            }
            result
        } as ExtractionResult<T>
    }

    private suspend fun <T> cached(cache: CacheService, area: String, key: String, serializer: KSerializer<T>): T? {
        val cached = runCatching { cache.get(key) }
            .onFailure { logger.warn("cache event=get_failed area={} key={} error={}", area, key, it.message) }
            .getOrNull()
        if (cached != null) {
            val decoded = runCatching { CacheJson.decodeFromString(serializer, cached) }
            decoded.getOrNull()?.let {
                logger.info("cache event=hit area={} key={}", area, key)
                return it
            }
            logger.warn(
                "cache event=decode_failed area={} key={} error={}",
                area,
                key,
                decoded.exceptionOrNull()?.message,
            )
        }
        return null
    }

    private suspend fun <T> store(
        cache: CacheService,
        area: String,
        key: String,
        serializer: KSerializer<T>,
        data: T,
        ttlSeconds: Long,
    ) {
        if (ttlSeconds <= 0L) return
        runCatching { cache.set(key, CacheJson.encodeToString(serializer, data), ttlSeconds) }
            .onSuccess { logger.info("cache event=store area={} key={} ttlSeconds={}", area, key, ttlSeconds) }
            .onFailure {
                logger.warn("cache event=set_failed area={} key={} error={}", area, key, it.message)
            }
    }
}
