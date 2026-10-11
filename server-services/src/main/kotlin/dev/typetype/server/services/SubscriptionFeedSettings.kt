package dev.typetype.server.services

internal object SubscriptionFeedSettings {
    private const val MAX_CONCURRENT_FETCHES = 20
    private const val DEFAULT_FRESHNESS_SECONDS = 60L

    val maxConcurrentFetches: Int
        get() = System.getenv("SUBSCRIPTION_FEED_MAX_CONCURRENT_FETCHES")?.toIntOrNull()
            ?.coerceIn(1, MAX_CONCURRENT_FETCHES)
            ?: MAX_CONCURRENT_FETCHES

    val freshnessMs: Long
        get() = 1_000L * (
            System.getenv("SUBSCRIPTION_FEED_FRESHNESS_SECONDS")?.toLongOrNull()
                ?.coerceIn(DEFAULT_FRESHNESS_SECONDS, 86_400L)
                ?: DEFAULT_FRESHNESS_SECONDS
            )
}
