package dev.typetype.server

import dev.typetype.server.cache.CacheService
import java.util.concurrent.ConcurrentHashMap

class RecommenderRoutesTestCache : CacheService {
    val values = ConcurrentHashMap<String, String>()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun set(key: String, value: String, ttlSeconds: Long) {
        values[key] = value
    }

    override suspend fun increment(key: String, ttlSeconds: Long): Long =
        values.merge(key, "1") { old, _ -> (old.toLong() + 1).toString() }.orEmpty().toLong()

    override suspend fun delete(key: String) {
        values.remove(key)
    }
}
