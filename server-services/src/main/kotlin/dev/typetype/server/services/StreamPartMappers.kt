package dev.typetype.server.services

import dev.typetype.server.models.StreamPartItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem

internal fun StreamInfoItem.toStreamPartItem(page: Int): StreamPartItem = StreamPartItem(
    page = page,
    title = name.orEmpty(),
    url = url.orEmpty(),
    thumbnailUrl = thumbnailUrl.toAbsoluteUrl(),
    duration = duration,
)
