package dev.typetype.server.services

import dev.typetype.server.cache.CacheService

private const val WINDOW_SECONDS = 60L
private const val MILLIS_PER_WINDOW = WINDOW_SECONDS * 1_000L

/**
 * One counter for the whole instance: every video YouTube is made to open counts, whoever
 * asked for it: the recommender, someone pressing play, a background repair after an import.
 * The recommender comes last: it only takes what the others left under the limit, and asks
 * nothing at all for a while once YouTube has refused a request (its anti-bot wall), so it
 * never makes that wall last longer for the people watching. Past the limit its request is
 * refused and the page is built without that source.
 */
class RecommenderStreamLimiter(
    private val cache: CacheService,
    private val limitPerMinute: suspend () -> Int,
    private val wallCooldownSeconds: Long = DEFAULT_WALL_COOLDOWN_SECONDS,
    private val now: () -> Long = System::currentTimeMillis,
) : StreamRequestWatch.Listener {
    suspend fun tryAcquire(): Boolean {
        val limit = limitPerMinute()
        if (limit <= 0 || walled()) return false
        val used = count() ?: return false
        return used <= limit
    }

    override suspend fun opened(alreadyCounted: Boolean, blocked: Boolean) {
        if (!alreadyCounted) count()
        if (blocked) runCatching { cache.set(WALL_KEY, now().toString(), wallCooldownSeconds) }
    }

    override suspend fun paused(): Boolean = walled()

    override suspend fun admit(): Boolean = tryAcquire()

    suspend fun usedThisMinute(): Long =
        runCatching { cache.get(windowKey())?.toLongOrNull() }.getOrNull() ?: 0L

    suspend fun walled(): Boolean = runCatching { cache.get(WALL_KEY) }.getOrNull() != null

    private suspend fun count(): Long? = runCatching { cache.increment(windowKey(), WINDOW_SECONDS * 2) }.getOrNull()

    private fun windowKey(): String = "recommender:stream-requests:v1:${now() / MILLIS_PER_WINDOW}"

    companion object {
        const val DEFAULT_WALL_COOLDOWN_SECONDS = 900L
        private const val WALL_KEY = "recommender:stream-wall:v1"
    }
}
