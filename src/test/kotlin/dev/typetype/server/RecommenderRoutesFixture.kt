package dev.typetype.server

import dev.typetype.server.cache.CacheService
import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.models.VideoItem
import dev.typetype.server.routes.adminRecommenderRoutes
import dev.typetype.server.routes.homeRecommendationRoutes
import dev.typetype.server.routes.recommenderFeedbackRoutes
import dev.typetype.server.routes.recommenderRoutes
import dev.typetype.server.services.AdminSettingsService
import dev.typetype.server.services.AuthService
import dev.typetype.server.services.BlockedService
import dev.typetype.server.services.ChannelService
import dev.typetype.server.services.FavoritesService
import dev.typetype.server.services.HistoryService
import dev.typetype.server.services.HomeRecommendationService
import dev.typetype.server.services.ProgressService
import dev.typetype.server.services.RecommenderConfig
import dev.typetype.server.services.SubscriptionsService
import dev.typetype.server.services.TrendingService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach

abstract class RecommenderRoutesFixture {
    protected val cache = RecommenderRoutesTestCache()
    protected val trendingService: TrendingService = mockk()
    protected val channelService: ChannelService = mockk()
    protected val mockCache: CacheService = mockk()
    protected val adminSettings = AdminSettingsService()
    protected val auth = AuthService.fixed(TEST_USER_ID)
    protected val recommender = FakeRecommender()
    protected val resolverDeps = homeResolverDependencies(
        subscriptions = SubscriptionsService(),
        channelService = channelService,
        cache = mockCache,
        trendingService = trendingService,
    )
    protected val currentHome = HomeRecommendationService(poolResolver = buildHomeResolver(resolverDeps))

    @BeforeEach
    fun clean() {
        TestDatabase.truncateAll()
        coEvery { mockCache.get(any()) } returns null
        coEvery { mockCache.set(any(), any(), any()) } returns Unit
        coEvery { trendingService.getTrending(any()) } returns ExtractionResult.Success(
            listOf(video("t1"), video("t2"), video("t3")),
        )
        recommender.answer("/health", """{"status":"ok"}""")
        recommender.answer("/v1/algorithms", ALGORITHMS)
        recommender.answer("/v1/history", "{}")
        recommender.answer("/v1/plan", """{"plan_id":"p","sources":[{"kind":"trending"}]}""")
        recommender.answer("/v1/rank", RANKED)
        recommender.answer("/v1/profile", """{"groups":[]}""")
    }

    @AfterEach
    fun stop() = recommender.close()

    protected fun services(installed: Boolean, chunk: String? = null): RecommenderServices = RecommenderServices(
        RecommenderDependencies(
            cache = cache,
            adminSettingsService = adminSettings,
            historyService = HistoryService(),
            progressService = ProgressService(),
            favoritesService = FavoritesService(),
            subscriptionsService = SubscriptionsService(),
            subscriptionFeedService = mockk(),
            blockedService = BlockedService(),
            settingsService = dev.typetype.server.services.SettingsService(),
            searchService = mockk(),
            channelService = channelService,
            streamService = mockk(),
            trendingService = trendingService,
        ),
        RecommenderConfig.fromEnvironment(
            env = { name ->
                when (name) {
                    "RECOMMENDER_SERVICE_URL" -> recommender.url.takeIf { installed }
                    "RECOMMENDER_HISTORY_CHUNK_ITEMS" -> chunk
                    else -> null
                }
            },
            internalToken = null,
        ),
    )

    protected fun withApp(
        installed: Boolean = true,
        chunk: String? = null,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val services = services(installed, chunk)
        application {
            install(ContentNegotiation) { json() }
            routing {
                recommenderRoutes(services, auth)
                recommenderFeedbackRoutes(services, auth)
                adminRecommenderRoutes(auth, services.adminService)
                homeRecommendationRoutes(
                    recommendationService = currentHome,
                    authService = auth,
                    blockedService = resolverDeps.blockedService,
                    recommenderHomeService = services.homeService,
                )
            }
        }
        block()
        services.close()
    }

    protected suspend fun ApplicationTestBuilder.call(path: String): HttpResponse =
        client.get(path) { header(HttpHeaders.Authorization, "Bearer test-jwt") }

    protected suspend fun ApplicationTestBuilder.save(body: String): HttpResponse =
        client.put("/recommendations/settings") {
            header(HttpHeaders.Authorization, "Bearer test-jwt")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    protected suspend fun ApplicationTestBuilder.opinion(body: String): HttpResponse =
        client.post("/recommendations/feedback") {
            header(HttpHeaders.Authorization, "Bearer test-jwt")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    protected suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    protected fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    protected fun video(id: String): VideoItem = VideoItem(
        id = id,
        title = "Video $id",
        url = "https://www.youtube.com/watch?v=$id",
        thumbnailUrl = "",
        uploaderName = "Channel $id",
        uploaderUrl = "https://www.youtube.com/channel/$id",
        uploaderAvatarUrl = "",
        duration = 300,
        viewCount = 10,
        uploadDate = "",
        streamType = "video_stream",
        isShortFormContent = false,
        uploaderVerified = false,
        shortDescription = null,
    )

    companion object {
        @BeforeAll
        @JvmStatic
        fun initDb() = TestDatabase.setup()

        const val ALGORITHMS = """{"default_algorithm":"signals","algorithms":[{"id":"subscriptions","uses":["subscriptions","not_interested"]},{"id":"signals","uses":["history","subscriptions","favorites","not_interested","shown"]}]}"""
        const val RANKED = """{"trace":{"sources":[],"candidates_received":3,
            "dropped":[{"url":"https://www.youtube.com/watch?v=t3","why":"seen"}],
            "scores":[{"url":"https://www.youtube.com/watch?v=t2","total":0.9,"factors":{"topic":0.9}}],
            "rows":[{"id":"r","why":"because"}]},"rows":[{"id":"r","title":"Row","reason":{"kind":"topic","ref":"s"},"videos":[
            {"url":"https://www.youtube.com/watch?v=t2"},{"url":"https://www.youtube.com/watch?v=t1"}]}]}"""
    }
}
