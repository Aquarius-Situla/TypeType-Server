package dev.typetype.server

import dev.typetype.server.models.AdminSettingsItem
import dev.typetype.server.models.HomeRecommendationRow
import dev.typetype.server.models.RecommenderSettingsItem
import dev.typetype.server.services.AdminSettingsService
import dev.typetype.server.services.RecommenderAlgorithmCatalog
import dev.typetype.server.services.RecommenderConfig
import dev.typetype.server.services.RecommenderHomeService
import dev.typetype.server.services.RecommenderMetrics
import dev.typetype.server.services.RecommenderPage
import dev.typetype.server.services.RecommenderPageBuilder
import dev.typetype.server.services.RecommenderPageStore
import dev.typetype.server.services.RecommenderSettingsService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecommenderHomeServiceTest {
    private val admin: AdminSettingsService = mockk()
    private val settings: RecommenderSettingsService = mockk()
    private val catalog: RecommenderAlgorithmCatalog = mockk()
    private val builder: RecommenderPageBuilder = mockk()
    private val metrics = RecommenderMetrics()
    private val videos = (1..5).map { recommenderTestVideo("v$it") }
    private val page = RecommenderPage(
        algorithm = "signals",
        rows = listOf(HomeRecommendationRow("row", "Row", "topic", null, videos.map { it.url })),
        items = videos,
    )

    private fun service(config: RecommenderConfig = recommenderTestConfig()): RecommenderHomeService {
        coEvery { admin.get() } returns AdminSettingsItem(recommenderEnabled = true)
        coEvery { settings.get(any()) } returns RecommenderSettingsItem(enabled = true)
        coEvery { catalog.defaultId(any()) } returns "signals"
        coEvery { builder.build(any()) } returns page
        return RecommenderHomeService(
            config = config,
            adminSettingsService = admin,
            settingsService = settings,
            catalog = catalog,
            pageBuilder = builder,
            pageStore = RecommenderPageStore(RecommenderTestCache(), config),
            metrics = metrics,
        )
    }

    private suspend fun RecommenderHomeService.home(cursor: String? = null) =
        getHome(userId = "u", limit = 2, rawCursor = cursor, locale = "en", timezone = "UTC")

    @Test
    fun `a crowd is served a few pages at a time and everyone gets theirs`() = runBlocking {
        val home = service(recommenderTestConfig(pageDeadlineMs = 10_000, parallelBuilds = 2))
        val atOnce = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        coEvery { builder.build(any()) } coAnswers {
            most.accumulateAndGet(atOnce.incrementAndGet(), ::maxOf)
            delay(40)
            atOnce.decrementAndGet()
            page
        }
        val answers = kotlinx.coroutines.coroutineScope {
            (1..12).map { person ->
                async {
                    home.getHome(userId = "u$person", limit = 2, rawCursor = null, locale = "en", timezone = "UTC")
                }
            }.map { it.await() }
        }
        assertEquals(2, most.get())
        assertEquals(12, answers.count { it?.algorithm == "signals" })
    }

    @Test
    fun `an instance without the service keeps its home page`() = runBlocking {
        val home = service(recommenderTestConfig(serviceUrl = null))
        assertNull(home.home())
        coVerify(exactly = 0) { builder.build(any()) }
    }

    @Test
    fun `the administrator switch and the person's own choice are both needed`() = runBlocking {
        val home = service()
        coEvery { admin.get() } returns AdminSettingsItem(recommenderEnabled = false)
        assertNull(home.home())
        coEvery { admin.get() } returns AdminSettingsItem(recommenderEnabled = true)
        coEvery { settings.get(any()) } returns RecommenderSettingsItem(enabled = false)
        assertNull(home.home())
        assertEquals(0, metrics.fallbacks.get())
    }

    @Test
    fun `a page is sliced by its cursor`() = runBlocking {
        val home = service()
        val first = checkNotNull(home.home())
        assertEquals(listOf("v1", "v2"), first.items.map { it.id })
        assertEquals("signals", first.algorithm)
        assertEquals(true, first.hasMore)
        val second = checkNotNull(home.home(first.nextCursor))
        assertEquals(listOf("v3", "v4"), second.items.map { it.id })
        val last = checkNotNull(home.home(second.nextCursor))
        assertEquals(listOf("v5"), last.items.map { it.id })
        assertEquals(false, last.hasMore)
        coVerify(exactly = 1) { builder.build(any()) }
    }

    @Test
    fun `a second visit is served from the kept page`() = runBlocking {
        val home = service()
        checkNotNull(home.home())
        checkNotNull(home.home())
        assertEquals(1, metrics.pagesFromCache.get())
        assertEquals(0, metrics.fallbacks.get())
    }

    @Test
    fun `a recommender that fails gives the home page back`() = runBlocking {
        val home = service()
        coEvery { builder.build(any()) } returns null
        assertNull(home.home())
        assertEquals(1, metrics.fallbacks.get())
    }

    @Test
    fun `a recommender that is late gives the home page back`() = runBlocking {
        val home = service(recommenderTestConfig(pageDeadlineMs = 50))
        coEvery { builder.build(any()) } coAnswers {
            delay(5_000)
            page
        }
        assertNull(home.home())
        assertEquals(1, metrics.fallbacks.get())
        home.close()
    }

    @Test
    fun `visits while a page is being built wait for that page and build no other`() = runBlocking {
        val home = service(recommenderTestConfig(pageDeadlineMs = 50))
        coEvery { builder.build(any()) } coAnswers {
            delay(300)
            page
        }
        assertNull(home.home())
        assertEquals(true, home.isPreparing("u"))
        assertNull(home.home())
        delay(500)
        assertEquals(false, home.isPreparing("u"))
        coVerify(exactly = 1) { builder.build(any()) }
        val ready = checkNotNull(home.home())
        assertEquals(listOf("v1", "v2"), ready.items.map { it.id })
        assertEquals(listOf("v3", "v4"), checkNotNull(home.home(ready.nextCursor)).items.map { it.id })
        home.close()
    }

    @Test
    fun `an algorithm with nothing to show is not asked again on the next visit`() = runBlocking {
        val home = service()
        coEvery { builder.build(any()) } returns page.copy(items = emptyList(), rows = emptyList())
        assertNull(home.home())
        assertNull(home.home())
        assertNull(home.home())
        coVerify(exactly = 1) { builder.build(any()) }
        assertEquals(false, home.isPreparing("u"))
    }
}
