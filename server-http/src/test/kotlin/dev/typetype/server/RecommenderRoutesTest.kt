package dev.typetype.server

import dev.typetype.server.cache.CacheJson
import dev.typetype.server.cache.CacheService
import dev.typetype.server.models.AdminSettingsItem
import dev.typetype.server.models.RecommenderSettingsItem
import dev.typetype.server.routes.recommenderRoutes
import dev.typetype.server.services.AdminSettingsCache
import dev.typetype.server.services.AdminSettingsService
import dev.typetype.server.services.AuthService
import dev.typetype.server.services.BlockedService
import dev.typetype.server.services.ChannelService
import dev.typetype.server.services.FavoritesService
import dev.typetype.server.services.HistoryService
import dev.typetype.server.services.ProgressService
import dev.typetype.server.services.RecommenderConfig
import dev.typetype.server.services.SearchService
import dev.typetype.server.services.SettingsService
import dev.typetype.server.services.StreamService
import dev.typetype.server.services.SubscriptionFeedService
import dev.typetype.server.services.SubscriptionsService
import dev.typetype.server.services.TrendingService
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecommenderRoutesTest {
    @AfterEach
    fun clearAdminSettings() {
        AdminSettingsCache.clear()
    }

    @Test
    fun `profile route refuses to send data before recommendations are enabled`() = testApplication {
        val cache = RecommenderRouteTestCache()
        cache.values["recommender:settings:v1:$TEST_USER_ID"] =
            CacheJson.encodeToString(RecommenderSettingsItem.serializer(), RecommenderSettingsItem(enabled = false))
        cache.values["recommender:algorithms:v1:en"] =
            CacheJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
                "algorithms" to kotlinx.serialization.json.JsonArray(
                    listOf(kotlinx.serialization.json.JsonObject(mapOf("id" to kotlinx.serialization.json.JsonPrimitive("test")))),
                ),
                "default_algorithm" to kotlinx.serialization.json.JsonPrimitive("test"),
            )))
        AdminSettingsCache.set(AdminSettingsItem(recommenderEnabled = true))
        val services = RecommenderServices(
            RecommenderDependencies(
                cache = cache,
                adminSettingsService = AdminSettingsService(),
                historyService = mockk(relaxed = true),
                progressService = mockk(relaxed = true),
                favoritesService = mockk(relaxed = true),
                subscriptionsService = mockk(relaxed = true),
                subscriptionFeedService = mockk(relaxed = true),
                blockedService = mockk(relaxed = true),
                settingsService = mockk(relaxed = true),
                searchService = mockk(relaxed = true),
                channelService = mockk(relaxed = true),
                streamService = mockk(relaxed = true),
                trendingService = mockk(relaxed = true),
            ),
            testConfig(),
        )

        try {
            application {
                install(ContentNegotiation) { json() }
                routing { recommenderRoutes(services, AuthService.fixed(TEST_USER_ID)) }
            }
            val response = client.get("/recommendations/profile?locale=en") {
                headers.append(HttpHeaders.Authorization, "Bearer test-jwt")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        } finally {
            services.close()
        }
    }

    private fun testConfig() = RecommenderConfig(
        serviceUrl = "http://127.0.0.1:1",
        internalToken = null,
        requestTimeoutMs = 100,
        pageDeadlineMs = 100,
        parallelBuilds = 1,
        streamRequestsPerMinute = 1,
        streamWallCooldownSeconds = 1,
        relatedTtlSeconds = 1,
        searchTtlSeconds = 1,
        channelTtlSeconds = 1,
        pageTtlSeconds = 1,
        settingsTtlSeconds = 60,
        historyKeyTtlSeconds = 60,
        historyMaxItems = 100,
        favoritesMaxItems = 100,
        historyChunkItems = 50,
        pageVariants = 1,
        maxSourceParallelism = 1,
    )
}

private class RecommenderRouteTestCache : CacheService {
    val values = mutableMapOf<String, String>()

    override suspend fun get(key: String): String? = values[key]
    override suspend fun set(key: String, value: String, ttlSeconds: Long) {
        values[key] = value
    }
    override suspend fun delete(key: String) {
        values.remove(key)
    }
}
