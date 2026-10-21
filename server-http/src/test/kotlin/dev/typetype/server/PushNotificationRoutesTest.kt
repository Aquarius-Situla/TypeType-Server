package dev.typetype.server

import dev.typetype.server.db.DatabaseFactory
import dev.typetype.server.db.tables.PushNotificationDeliveriesTable
import dev.typetype.server.routes.pushNotificationRoutes
import dev.typetype.server.services.AuthService
import dev.typetype.server.services.ChannelNotificationPreferenceService
import dev.typetype.server.services.PushDevice
import dev.typetype.server.services.PushDeviceRegistry
import dev.typetype.server.services.PushEndpointSender
import dev.typetype.server.services.PushNotificationDeliveryStore
import dev.typetype.server.services.PushNotificationService
import dev.typetype.server.services.PushSendResult
import dev.typetype.server.services.SubscriptionFeedService
import dev.typetype.server.services.SubscriptionsService
import dev.typetype.server.services.NotificationsService
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PushNotificationRoutesTest {
    private val auth = AuthService.fixed(TEST_USER_ID)
    private val eventId = "a".repeat(64)

    companion object {
        private const val ACK_ROUTE = "/notifications/push/deliveries/acknowledgement"

        @BeforeAll
        @JvmStatic
        fun initDb() = TestDatabase.setup()
    }

    @BeforeEach
    fun clean() = TestDatabase.truncateAll()

    @Test
    fun `delivery acknowledgement requires authentication`() = withApp {
        val response = client.post(ACK_ROUTE) {
            contentType(ContentType.Application.Json)
            setBody(request("posted"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `client acknowledgement is stored against its stable device id`() = withApp {
        val rowId = "internal-row-id"
        val deviceId = "android-installation-id"
        val device = PushDevice(
            id = rowId,
            deviceId = deviceId,
            userId = TEST_USER_ID,
            endpoint = "https://push.example/endpoint",
            p256dh = "public",
            auth = "secret",
        )
        val store = PushNotificationDeliveryStore { 10_000L }
        DatabaseFactory.query {
            store.claim(eventId, TEST_USER_ID, device.deviceId, true, 10_000L)
        }
        val response = client.post(ACK_ROUTE) {
            headers.append(HttpHeaders.Authorization, "Bearer test-jwt")
            contentType(ContentType.Application.Json)
            setBody(request("posted", deviceId))
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val savedStatus = DatabaseFactory.query {
            PushNotificationDeliveriesTable.selectAll().where {
                (PushNotificationDeliveriesTable.eventId eq eventId) and
                    (PushNotificationDeliveriesTable.userId eq TEST_USER_ID)
            }.single()[PushNotificationDeliveriesTable.status]
        }
        assertEquals("client_posted", savedStatus)
        assertFalse(response.bodyAsText().isNotBlank())
    }

    @Test
    fun `invalid event identifiers are rejected`() = withApp {
        val response = client.post(ACK_ROUTE) {
            headers.append(HttpHeaders.Authorization, "Bearer test-jwt")
            contentType(ContentType.Application.Json)
            setBody(request("posted", eventId = "bad"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    private fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                pushNotificationRoutes(
                    service = service(),
                    authService = auth,
                    notificationsService = mockk(relaxed = true),
                )
            }
        }
        block()
    }

    private fun service() = PushNotificationService(
        subscriptionsService = mockk<SubscriptionsService>(),
        subscriptionFeedService = mockk<SubscriptionFeedService>(),
        preferenceService = mockk<ChannelNotificationPreferenceService>(),
        instanceId = "test-instance",
        deviceRegistry = PushDeviceRegistry(),
        sender = object : PushEndpointSender {
            override suspend fun send(
                endpoint: String,
                eventId: String,
                payload: String,
                p256dh: String,
                auth: String,
            ): PushSendResult = PushSendResult.Delivered
        },
    )

    private fun request(outcome: String, deviceId: String = "android-installation-id", eventId: String = this.eventId) =
        """{"eventId":"$eventId","deviceId":"$deviceId","outcome":"$outcome"}"""

}
