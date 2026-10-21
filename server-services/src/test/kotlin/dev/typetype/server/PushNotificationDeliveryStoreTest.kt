package dev.typetype.server

import dev.typetype.server.db.DatabaseFactory
import dev.typetype.server.services.DeliveryWork
import dev.typetype.server.services.PushDeliveryAcknowledgementResult
import dev.typetype.server.services.PushDevice
import dev.typetype.server.services.PushNotificationDeliveryStore
import dev.typetype.server.services.PushNotificationDeliveryStatus
import org.junit.jupiter.api.Assertions.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PushNotificationDeliveryStoreTest {
    private val store = PushNotificationDeliveryStore { 1_000L }

    companion object {
        @BeforeAll
        @JvmStatic
        fun initDb() = TestDatabase.setup()
    }

    @BeforeEach
    fun clean() = TestDatabase.truncateAll()

    @Test
    fun `does not create historical delivery and retries an existing pending delivery`() = runTest {
        assertFalse(DatabaseFactory.query { store.claim("event", "user", "device", false, 1_000L) })
        assertTrue(DatabaseFactory.query { store.claim("event", "user", "device", true, 1_000L) })
        assertFalse(DatabaseFactory.query { store.claim("event", "user", "device", false, 1_001L) })
        assertTrue(DatabaseFactory.query { store.claim("event", "user", "device", false, 31_001L) })
    }

    @Test
    fun `unacknowledged distributor delivery is retried and client acknowledgement stops retries`() = runTest {
        val device = device()
        val work = DeliveryWork("event", device, "{}")
        assertTrue(DatabaseFactory.query { store.claim(work.eventId, "user", device.deviceId, true, 1_000L) })
        store.update(work, PushNotificationDeliveryStatus.AWAITING_ACK, null)

        assertTrue(DatabaseFactory.query { store.claim(work.eventId, "user", device.deviceId, false, 31_001L) })
        assertEquals(
            PushDeliveryAcknowledgementResult.Recorded,
            store.acknowledge(work.eventId, "user", device.deviceId, "posted", null),
        )
        assertEquals(
            PushDeliveryAcknowledgementResult.AlreadyRecorded,
            store.acknowledge(work.eventId, "user", device.deviceId, "posted", null),
        )
        assertFalse(DatabaseFactory.query { store.claim(work.eventId, "user", device.deviceId, false, 62_000L) })
    }

    @Test
    fun `delivery acknowledgement is scoped to event user and device`() = runTest {
        val device = device()
        assertTrue(DatabaseFactory.query { store.claim("event", "user", device.deviceId, true, 1_000L) })
        assertEquals(
            PushDeliveryAcknowledgementResult.NotFound,
            store.acknowledge("event", "other-user", device.deviceId, "posted", null),
        )
        assertEquals(
            PushDeliveryAcknowledgementResult.NotFound,
            store.acknowledge("event", "user", "other-device", "posted", null),
        )
    }

    @Test
    fun `unacknowledged delivery retry count is bounded`() = runTest {
        val device = device()
        val work = DeliveryWork("event", device, "{}")
        assertTrue(DatabaseFactory.query { store.claim(work.eventId, "user", device.deviceId, true, 1_000L) })
        store.update(work, PushNotificationDeliveryStatus.AWAITING_ACK, null)
        assertTrue(DatabaseFactory.query { store.claim(work.eventId, "user", device.deviceId, false, 31_001L) })
        store.update(work, PushNotificationDeliveryStatus.AWAITING_ACK, null)
        assertTrue(DatabaseFactory.query { store.claim(work.eventId, "user", device.deviceId, false, 62_001L) })
        store.update(work, PushNotificationDeliveryStatus.AWAITING_ACK, null)

        assertFalse(DatabaseFactory.query { store.claim(work.eventId, "user", device.deviceId, false, 93_001L) })
    }

    private fun device() = PushDevice(
        id = "server-row-id",
        deviceId = "client-device-id",
        userId = "user",
        endpoint = "https://push.example/endpoint",
        p256dh = "public",
        auth = "secret",
    )
}
