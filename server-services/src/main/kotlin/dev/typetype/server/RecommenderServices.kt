package dev.typetype.server

import kotlinx.coroutines.withContext
import dev.typetype.server.services.RelatedVideosService
import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.cache.CacheService
import dev.typetype.server.downloader.OkHttpDownloader
import dev.typetype.server.services.AdminSettingsService
import dev.typetype.server.services.BlockedService
import dev.typetype.server.services.ChannelService
import dev.typetype.server.services.FavoritesService
import dev.typetype.server.services.HistoryService
import dev.typetype.server.services.ProgressService
import dev.typetype.server.services.RecommenderAdminService
import dev.typetype.server.services.RecommenderAlgorithmCatalog
import dev.typetype.server.services.RecommenderClient
import dev.typetype.server.services.RecommenderConfig
import dev.typetype.server.services.RecommenderDataService
import dev.typetype.server.services.RecommenderFeedbackService
import dev.typetype.server.services.RecommenderHistoryService
import dev.typetype.server.services.RecommenderHomeService
import dev.typetype.server.services.RecommenderKnownVideos
import dev.typetype.server.services.RecommenderMetrics
import dev.typetype.server.services.RecommenderPageBuilder
import dev.typetype.server.services.RecommenderPageStore
import dev.typetype.server.services.RecommenderProfileIllustrator
import dev.typetype.server.services.RecommenderProfileService
import dev.typetype.server.services.RecommenderSettingsService
import dev.typetype.server.services.RecommenderSnapshotService
import dev.typetype.server.services.RecommenderSourceCache
import dev.typetype.server.services.RecommenderSourceFetcher
import dev.typetype.server.services.RecommenderSourceLoader
import dev.typetype.server.services.RecommenderStreamLimiter
import dev.typetype.server.services.StreamRequestWatch
import dev.typetype.server.services.RecommenderUserDataInvalidation
import dev.typetype.server.services.SearchService
import dev.typetype.server.services.SettingsService
import dev.typetype.server.services.StreamService
import dev.typetype.server.services.SubscriptionFeedService
import dev.typetype.server.services.SubscriptionsService
import dev.typetype.server.services.TrendingService

data class RecommenderDependencies(
    val cache: CacheService,
    val adminSettingsService: AdminSettingsService,
    val historyService: HistoryService,
    val progressService: ProgressService,
    val favoritesService: FavoritesService,
    val subscriptionsService: SubscriptionsService,
    val subscriptionFeedService: SubscriptionFeedService,
    val blockedService: BlockedService,
    val settingsService: SettingsService,
    val searchService: SearchService,
    val channelService: ChannelService,
    val streamService: StreamService,
    val trendingService: TrendingService,
    val streamRequestWatch: StreamRequestWatch? = null,
    val relatedVideosService: RelatedVideosService? = null,
)

class RecommenderServices(
    private val deps: RecommenderDependencies,
    val config: RecommenderConfig = RecommenderConfig.fromEnvironment(),
) : AutoCloseable {
    val metrics = RecommenderMetrics()
    val client = RecommenderClient(config)
    val settingsService = RecommenderSettingsService(
        deps.cache,
        config,
        onByDefault = { deps.adminSettingsService.get().recommenderDefaultOn },
    )
    val catalog = RecommenderAlgorithmCatalog(client, deps.cache, config, deps.adminSettingsService)
    val streamLimiter = RecommenderStreamLimiter(
        cache = deps.cache,
        limitPerMinute = {
            deps.adminSettingsService.get().recommenderStreamRequestsPerMinute.takeIf { it > 0 }
                ?: config.streamRequestsPerMinute
        },
        wallCooldownSeconds = config.streamWallCooldownSeconds,
    ).also { limiter ->
        if (config.installed) {
            deps.streamRequestWatch?.let { watch ->
                watch.listener = limiter
                OkHttpDownloader.onProviderRefusal = watch::refused
            }
        }
    }
    private val historyService = RecommenderHistoryService(client, deps.cache, config)
    val feedbackService = RecommenderFeedbackService(settingsService, defaultAlgorithmId = { catalog.defaultId(DEFAULT_LOCALE) })
    private val snapshotService = RecommenderSnapshotService(
        historyService,
        deps.subscriptionsService,
        deps.blockedService,
        feedbackService::notForMeUrls,
    )
    val dataService = RecommenderDataService(
        deps.subscriptionsService,
        deps.blockedService,
        feedbackService,
        deps.settingsService,
    )
    val profileService = RecommenderProfileService(client, snapshotService)
    val profileIllustrator = RecommenderProfileIllustrator(deps.subscriptionsService)
    private val sourceFetcher = RecommenderSourceFetcher(
        loader = RecommenderSourceLoader(
            deps.searchService,
            deps.channelService,
            deps.relatedVideosService ?: deps.streamService.asRelatedVideos(),
            deps.trendingService,
            RecommenderSourceCache(deps.cache, metrics),
            streamLimiter,
            metrics,
            config,
        ),
        subscriptionFeed = deps.subscriptionFeedService::getAll,
        knownVideos = RecommenderKnownVideos::find,
        config = config,
    )
    private val pageStore = RecommenderPageStore(deps.cache, config)
    val homeService = RecommenderHomeService(
        config = config,
        adminSettingsService = deps.adminSettingsService,
        settingsService = settingsService,
        catalog = catalog,
        pageBuilder = RecommenderPageBuilder(client, snapshotService, sourceFetcher, metrics),
        pageStore = pageStore,
        metrics = metrics,
    )

    val adminService = RecommenderAdminService(config, client, catalog, metrics, streamLimiter, deps.adminSettingsService)

    init {
        RecommenderUserDataInvalidation.configure { userId ->
            settingsService.forget(userId)
            homeService.forgetPages(userId)
        }
    }

    suspend fun offered(): Boolean = config.installed && deps.adminSettingsService.get().recommenderEnabled

    suspend fun requestTimeoutMs(): Long =
        deps.adminSettingsService.get().recommenderTimeoutMs.takeIf { it > 0 }?.toLong() ?: config.requestTimeoutMs

    override fun close() {
        homeService.close()
    }

    private companion object {
        const val DEFAULT_LOCALE = "en"
    }
}

private fun StreamService.asRelatedVideos(): RelatedVideosService = RelatedVideosService { url ->
    val opened = withContext(StreamRequestWatch.AlreadyCounted.mark + StreamRequestWatch.NobodyWaiting.mark) {
        getStreamInfo(url)
    }
    when (opened) {
        is ExtractionResult.Success -> ExtractionResult.Success(opened.data.relatedStreams)
        is ExtractionResult.Failure -> opened
        is ExtractionResult.BadRequest -> opened
    }
}
