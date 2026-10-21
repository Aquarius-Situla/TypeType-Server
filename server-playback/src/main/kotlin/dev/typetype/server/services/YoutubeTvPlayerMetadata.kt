package dev.typetype.server.services

import com.grack.nanojson.JsonObject

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
