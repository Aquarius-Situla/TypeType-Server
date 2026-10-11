package dev.typetype.server.services

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * The videos the instance opens at the provider in a minute, everyone together: past a certain
 * number the provider refuses them all, for a long time. Under the limit nobody waits; over it
 * each video waits its turn, evenly spaced, and one that would wait too long is not asked at
 * all. 0 means no limit.
 */
class StreamOpenPace(
    perMinute: Int = System.getenv("YOUTUBE_OPENS_PER_MINUTE")?.toIntOrNull() ?: 0,
    private val longestWaitMs: Long = 30_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val gapMs = if (perMinute > 0) 60_000L / perMinute else 0L
    private val nextTurn = AtomicLong(0L)

    suspend fun turn(someoneWaits: Boolean = true): Boolean {
        if (gapMs == 0L) return true
        val patience = if (someoneWaits) longestWaitMs else 0L
        while (true) {
            val time = now()
            val taken = nextTurn.get()
            val mine = maxOf(taken, time)
            if (mine - time > patience) return false
            if (nextTurn.compareAndSet(taken, mine + gapMs)) {
                delay(mine - time)
                return true
            }
        }
    }
}
