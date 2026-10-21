package dev.typetype.server.services

import com.grack.nanojson.JsonObject

internal fun JsonObject.youtubeTvTitle(): String {
    val detailsTitle = getObject("videoDetails")
        ?.getString("title", "")
        .orEmpty()
        .trim()
    if (detailsTitle.isNotEmpty()) return detailsTitle

    return getObject("microformat")
        ?.getObject("playerMicroformatRenderer")
        ?.getObject("title")
        ?.getString("simpleText", "")
        .orEmpty()
        .trim()
}

internal fun JsonObject.youtubeTvChannelId(): String {
    val detailsChannelId = getObject("videoDetails")
        ?.getString("channelId", "")
        .orEmpty()
        .trim()
    if (detailsChannelId.isNotEmpty()) return detailsChannelId

    return getObject("microformat")
        ?.getObject("playerMicroformatRenderer")
        ?.getString("externalChannelId", "")
        .orEmpty()
        .trim()
}

internal fun JsonObject.youtubeTvUploaderName(): String {
    val author = getObject("videoDetails")
        ?.getString("author", "")
        .orEmpty()
        .trim()
    if (author.isNotEmpty()) return author

    return getObject("microformat")
        ?.getObject("playerMicroformatRenderer")
        ?.getString("ownerChannelName", "")
        .orEmpty()
        .trim()
}
