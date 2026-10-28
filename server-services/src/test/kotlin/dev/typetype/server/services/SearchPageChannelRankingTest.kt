package dev.typetype.server.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem

class SearchPageChannelRankingTest {
    @Test
    fun `verified Bilibili channels come first and keep their order`() {
        val page = page(
            channel(ServiceList.BiliBili.serviceId, "reupload", verified = false),
            channel(ServiceList.BiliBili.serviceId, "official", verified = true),
            channel(ServiceList.BiliBili.serviceId, "fan", verified = false),
            channel(ServiceList.BiliBili.serviceId, "creator", verified = true),
        )

        val names = page.toSearchPageResponse().channels.map { it.name }

        assertEquals(listOf("official", "creator", "reupload", "fan"), names)
    }

    @Test
    fun `other services keep the provider order`() {
        val page = page(
            channel(ServiceList.YouTube.serviceId, "first", verified = false),
            channel(ServiceList.YouTube.serviceId, "second", verified = true),
        )

        val names = page.toSearchPageResponse().channels.map { it.name }

        assertEquals(listOf("first", "second"), names)
    }

    private fun channel(serviceId: Int, name: String, verified: Boolean): InfoItem =
        ChannelInfoItem(serviceId, "https://example.com/$name", name).apply { isVerified = verified }

    private fun page(vararg items: InfoItem) = InfoItemsPage(items.toList(), null, emptyList())
}
