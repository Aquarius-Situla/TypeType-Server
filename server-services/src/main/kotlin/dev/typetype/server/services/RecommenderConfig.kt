package dev.typetype.server.services

data class RecommenderConfig(
    val serviceUrl: String?,
    val internalToken: String?,
    val requestTimeoutMs: Long,
    val pageDeadlineMs: Long,
    val parallelBuilds: Int,
    val streamRequestsPerMinute: Int,
    val streamWallCooldownSeconds: Long,
    val relatedTtlSeconds: Long,
    val searchTtlSeconds: Long,
    val channelTtlSeconds: Long,
    val pageTtlSeconds: Long,
    val settingsTtlSeconds: Long,
    val historyKeyTtlSeconds: Long,
    val historyMaxItems: Int,
    val favoritesMaxItems: Int,
    val historyChunkItems: Int,
    val pageVariants: Int,
    val maxSourceParallelism: Int,
) {
    val installed: Boolean get() = !serviceUrl.isNullOrBlank()

    companion object {
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 2_500L
        private const val DEFAULT_PAGE_DEADLINE_MS = 4_000L
        private const val DEFAULT_STREAM_REQUESTS_PER_MINUTE = 15L
        private const val DEFAULT_PARALLEL_BUILDS = 3L
        private const val DEFAULT_RELATED_TTL_SECONDS = 86_400L
        private const val DEFAULT_SEARCH_TTL_SECONDS = 21_600L
        private const val DEFAULT_CHANNEL_TTL_SECONDS = 1_800L
        private const val DEFAULT_PAGE_TTL_SECONDS = 1_800L
        private const val DEFAULT_SETTINGS_TTL_SECONDS = 600L
        private const val DEFAULT_HISTORY_KEY_TTL_SECONDS = 86_400L
        private const val DEFAULT_HISTORY_MAX_ITEMS = 100_000L
        private const val DEFAULT_FAVORITES_MAX_ITEMS = 50_000L
        private const val DEFAULT_HISTORY_CHUNK_ITEMS = 20_000L
        private const val DEFAULT_PAGE_VARIANTS = 3L
        private const val DEFAULT_SOURCE_PARALLELISM = 6L

        fun fromEnvironment(
            env: (String) -> String? = System::getenv,
            internalToken: String? = SecretConfigReader.read("RECOMMENDER_INTERNAL_TOKEN"),
        ): RecommenderConfig = RecommenderConfig(
            serviceUrl = env("RECOMMENDER_SERVICE_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() },
            internalToken = internalToken,
            requestTimeoutMs = env.long("RECOMMENDER_REQUEST_TIMEOUT_MS", DEFAULT_REQUEST_TIMEOUT_MS),
            pageDeadlineMs = env.long("RECOMMENDER_PAGE_DEADLINE_MS", DEFAULT_PAGE_DEADLINE_MS),
            parallelBuilds = env.long("RECOMMENDER_PARALLEL_BUILDS", DEFAULT_PARALLEL_BUILDS).toInt().coerceIn(1, 64),
            streamRequestsPerMinute = env.long(
                "RECOMMENDER_STREAM_REQUESTS_PER_MINUTE",
                DEFAULT_STREAM_REQUESTS_PER_MINUTE,
            ).toInt(),
            streamWallCooldownSeconds = env.long(
                "RECOMMENDER_STREAM_WALL_COOLDOWN_SECONDS",
                RecommenderStreamLimiter.DEFAULT_WALL_COOLDOWN_SECONDS,
            ),
            relatedTtlSeconds = env.long("RECOMMENDER_RELATED_TTL_SECONDS", DEFAULT_RELATED_TTL_SECONDS),
            searchTtlSeconds = env.long("RECOMMENDER_SEARCH_TTL_SECONDS", DEFAULT_SEARCH_TTL_SECONDS),
            channelTtlSeconds = env.long("RECOMMENDER_CHANNEL_TTL_SECONDS", DEFAULT_CHANNEL_TTL_SECONDS),
            pageTtlSeconds = env.long("RECOMMENDER_PAGE_TTL_SECONDS", DEFAULT_PAGE_TTL_SECONDS),
            settingsTtlSeconds = env.long("RECOMMENDER_SETTINGS_TTL_SECONDS", DEFAULT_SETTINGS_TTL_SECONDS),
            historyKeyTtlSeconds = env.long("RECOMMENDER_HISTORY_KEY_TTL_SECONDS", DEFAULT_HISTORY_KEY_TTL_SECONDS),
            historyMaxItems = env.long("RECOMMENDER_HISTORY_MAX_ITEMS", DEFAULT_HISTORY_MAX_ITEMS).toInt().coerceAtLeast(1),
            favoritesMaxItems = env.long("RECOMMENDER_FAVORITES_MAX_ITEMS", DEFAULT_FAVORITES_MAX_ITEMS).toInt().coerceAtLeast(1),
            historyChunkItems = env.long("RECOMMENDER_HISTORY_CHUNK_ITEMS", DEFAULT_HISTORY_CHUNK_ITEMS).toInt().coerceAtLeast(1),
            pageVariants = env.long("RECOMMENDER_PAGE_VARIANTS", DEFAULT_PAGE_VARIANTS).toInt().coerceAtLeast(1),
            maxSourceParallelism = env.long("RECOMMENDER_SOURCE_PARALLELISM", DEFAULT_SOURCE_PARALLELISM)
                .toInt().coerceAtLeast(1),
        )

        private fun ((String) -> String?).long(name: String, default: Long): Long =
            this(name)?.trim()?.toLongOrNull()?.takeIf { it >= 0L } ?: default
    }
}
