package dev.typetype.server.routes

internal fun playbackWindowRetryAfterMs(
    isLive: Boolean,
    initialWindowReady: Boolean,
    defaultRetryAfterMs: Long,
): Long = if (!isLive && !initialWindowReady) 150L else defaultRetryAfterMs
