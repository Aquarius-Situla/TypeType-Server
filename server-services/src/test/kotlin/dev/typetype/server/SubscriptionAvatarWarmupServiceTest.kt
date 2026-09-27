package dev.typetype.server

import dev.typetype.server.db.DatabaseFactory
import dev.typetype.server.db.tables.SubscriptionsTable
import dev.typetype.server.models.ChannelPlaylistsResponse
import dev.typetype.server.models.ChannelResponse
import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.models.SubscriptionItem
import dev.typetype.server.services.ChannelService
import dev.typetype.server.services.SubscriptionAvatarWarmupService
import dev.typetype.server.services.SubscriptionsService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SubscriptionAvatarWarmupServiceTest {
    private val channelUrl = "https://www.youtube.com/channel/UCWarmup"

    companion object {
        @BeforeAll
        @JvmStatic
        fun initDb(): Unit = TestDatabase.setup()
    }

    @BeforeEach
    fun clean(): Unit = TestDatabase.truncateAll()

    @Test
    fun `warmup persists a resolved channel avatar`() = runBlocking {
        insertSubscription()
        val service = SubscriptionAvatarWarmupService(channelService("https://avatar.test/warmup.jpg"))

        service.warm(TEST_USER_ID, listOf(channelUrl))

        assertEquals("https://avatar.test/warmup.jpg", storedAvatar())
    }

    @Test
    fun `warmup leaves a blank avatar when channel resolution has no avatar`() = runBlocking {
        insertSubscription()
        val service = SubscriptionAvatarWarmupService(channelService(""))

        service.warm(TEST_USER_ID, listOf(channelUrl))

        assertEquals("", storedAvatar())
    }

    private suspend fun insertSubscription(): Unit {
        SubscriptionsService().add(
            TEST_USER_ID,
            SubscriptionItem(
                channelUrl = channelUrl,
                name = "OHIOBOSS SATOYU",
                avatarUrl = "",
            ),
        )
    }

    private suspend fun storedAvatar(): String = DatabaseFactory.query {
        SubscriptionsTable.selectAll()
            .where {
                (SubscriptionsTable.userId eq TEST_USER_ID) and
                    (SubscriptionsTable.channelUrl eq channelUrl)
            }
            .single()[SubscriptionsTable.avatarUrl]
    }

    private fun channelService(avatarUrl: String): ChannelService = object : ChannelService {
        override suspend fun getChannel(
            url: String,
            nextpage: String?,
            sort: String?,
        ): ExtractionResult<ChannelResponse> = ExtractionResult.Success(
            ChannelResponse(
                name = "OHIOBOSS SATOYU",
                description = "",
                avatarUrl = avatarUrl,
                bannerUrl = "",
                subscriberCount = 0,
                isVerified = false,
                videos = emptyList(),
                nextpage = null,
            ),
        )

        override suspend fun getPlaylists(
            url: String,
            nextpage: String?,
        ): ExtractionResult<ChannelPlaylistsResponse> =
            ExtractionResult.BadRequest("Not used")
    }
}
