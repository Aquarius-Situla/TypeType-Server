package dev.typetype.server.services

import dev.typetype.server.models.HomeRecommendationsResponse
import dev.typetype.server.models.RecommenderPageTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class RecommenderHomeService(
    private val config: RecommenderConfig,
    private val adminSettingsService: AdminSettingsService,
    private val settingsService: RecommenderSettingsService,
    private val catalog: RecommenderAlgorithmCatalog,
    private val pageBuilder: RecommenderPageBuilder,
    private val pageStore: RecommenderPageStore,
    private val metrics: RecommenderMetrics,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val building = ConcurrentHashMap<String, Deferred<Pair<Int, RecommenderPage>?>>()
    private val turns = Semaphore(config.parallelBuilds)

    suspend fun getHome(userId: String, limit: Int, rawCursor: String?, locale: String, timezone: String): HomeRecommendationsResponse? {
        if (!config.installed) return null
        val admin = adminSettingsService.get()
        if (!admin.recommenderEnabled) return null
        val settings = settingsService.get(userId)
        if (!settings.enabled) return null
        val algorithmId = settings.algorithmId ?: catalog.defaultId(locale) ?: return fallback()
        val cursor = RecommenderPageCursor.decode(rawCursor)
        val wanted = cursor?.variant?.takeIf { it < config.pageVariants } ?: pageStore.nextVariant(userId)
        val timeoutMs = admin.recommenderTimeoutMs.takeIf { it > 0 }?.toLong() ?: config.requestTimeoutMs
        fun request(variant: Int) = RecommenderPageRequest(
            userId = userId,
            algorithmId = algorithmId,
            settings = settings,
            seed = pageStore.seedFor(variant),
            locale = locale,
            timezone = timezone,
            timeoutMs = timeoutMs,
        )
        var variant = wanted
        var page = pageStore.get(userId, algorithmId, settings, wanted)
        if (page != null && page.items.isEmpty()) return fallback()
        if (page == null && cursor == null) {
            val others = (0 until config.pageVariants).mapNotNull { other ->
                pageStore.get(userId, algorithmId, settings, other)?.let { other to it }
            }
            if (others.isNotEmpty() && others.all { it.second.items.isEmpty() }) return fallback()
            val kept = others.firstOrNull { it.second.items.isNotEmpty() }
            if (kept != null) {
                start(request(wanted), wanted)
                variant = kept.first
                page = kept.second
            }
        }
        if (page != null) {
            metrics.pagesFromCache.incrementAndGet()
        } else {
            val built = await(start(request(wanted), wanted)) ?: return fallback()
            variant = built.first
            page = built.second
        }
        val offset = cursor?.offset ?: 0
        val slice = page.items.drop(offset).take(limit)
        val next = (offset + slice.size).takeIf { it < page.items.size }
        val hidden = pageStore.hidden(userId)
        return HomeRecommendationsResponse(
            items = slice.filterNot { it.url in hidden },
            nextCursor = next?.let { RecommenderPageCursor(variant, it).encode() },
            hasMore = next != null,
            algorithm = page.algorithm,
            rows = if (hidden.isEmpty()) page.rows else page.rows.map { row -> row.copy(videoUrls = row.videoUrls - hidden) },
        )
    }

    suspend fun forgetPages(userId: String): Unit = pageStore.forget(userId)

    suspend fun hideVideo(userId: String, videoUrl: String, hide: Boolean = true): Unit = pageStore.hide(userId, videoUrl, hide)

    fun isPreparing(userId: String): Boolean = building.keys.any { it.startsWith("$userId:") }

    suspend fun lastTrace(userId: String): RecommenderPageTrace? = pageStore.lastTrace(userId)

    private fun start(request: RecommenderPageRequest, variant: Int): Deferred<Pair<Int, RecommenderPage>?> {
        val key = "${request.userId}:${request.algorithmId}:${request.settings.hashCode()}"
        return building.computeIfAbsent(key) {
            scope.async {
                try {
                    turns.withPermit {
                        val started = System.currentTimeMillis()
                        pageBuilder.build(request)?.let { page ->
                            pageStore.put(request.userId, request.settings, variant, page)
                            metrics.pagesBuilt.incrementAndGet()
                            metrics.buildMillis.addAndGet(System.currentTimeMillis() - started)
                            (variant to page).takeIf { page.items.isNotEmpty() }
                        }
                    }
                } finally {
                    building.remove(key)
                }
            }
        }
    }

    private suspend fun await(job: Deferred<Pair<Int, RecommenderPage>?>): Pair<Int, RecommenderPage>? =
        withTimeoutOrNull(config.pageDeadlineMs) { runCatching { job.await() }.getOrNull() }

    private fun fallback(): HomeRecommendationsResponse? {
        metrics.fallbacks.incrementAndGet()
        return null
    }

    override fun close() {
        scope.cancel()
    }
}
