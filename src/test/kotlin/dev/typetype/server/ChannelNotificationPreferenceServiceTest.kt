package dev.typetype.server

import dev.typetype.server.models.SubscriptionItem
import dev.typetype.server.services.ChannelNotificationPreferenceService
import dev.typetype.server.services.SubscriptionsService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChannelNotificationPreferenceServiceTest {
    private val subscriptions = SubscriptionsService()
    private val preferences = ChannelNotificationPreferenceService(subscriptions)

    companion object {
        private const val CHANNEL_URL = "https://www.youtube.com/channel/UCNotification"

        @BeforeAll
        @JvmStatic
        fun initDb(): Unit = TestDatabase.setup()
    }

    @BeforeEach
    fun clean(): Unit = TestDatabase.truncateAll()

    @Test
    fun `channel notifications are disabled until explicitly enabled`() = runTest {
        subscriptions.add(TEST_USER_ID, SubscriptionItem(CHANNEL_URL, "Channel", ""))

        assertEquals(false, preferences.list(TEST_USER_ID).single().enabled)

        preferences.set(TEST_USER_ID, CHANNEL_URL, true)

        assertEquals(true, preferences.list(TEST_USER_ID).single().enabled)
    }
}
