package dev.typetype.server.services

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonWriter

internal fun buildYoutubeTvOauthPlayerBody(
    clientName: String,
    videoId: String,
    clientVersion: String,
    visitorData: String?,
    livingRoomPoTokenId: String?,
    poToken: String?,
    cpn: String,
    signatureTimestamp: Long,
): String = JsonObject.builder()
    .`object`("context")
    .`object`("client")
    .value("clientName", clientName)
    .value("clientVersion", clientVersion)
    .value("hl", "en")
    .value("gl", "US")
    .let { builder ->
        if (visitorData.isNullOrBlank()) builder else builder.value("visitorData", visitorData)
    }
    .value("deviceMake", "Google")
    .value("deviceModel", "Chromecast")
    .value("osName", "Android")
    .value("osVersion", "10")
    .value("platform", "TV")
    .value("utcOffsetMinutes", 0)
    .apply {
        if (!livingRoomPoTokenId.isNullOrBlank()) {
            `object`("tvAppInfo").value("livingRoomPoTokenId", livingRoomPoTokenId).end()
        }
    }
    .end()
    .end()
    .`object`("playbackContext")
    .`object`("contentPlaybackContext")
    .value("html5Preference", "HTML5_PREF_WANTS")
    .value("lactMilliseconds", 60_000)
    .value("isInlinePlaybackNoAd", true)
    .value("signatureTimestamp", signatureTimestamp)
    .end()
    .end()
    .value("videoId", videoId)
    .value("cpn", cpn)
    .value("racyCheckOk", true)
    .value("contentCheckOk", true)
    .apply {
        if (!poToken.isNullOrBlank()) {
            `object`("serviceIntegrityDimensions").value("poToken", poToken).end()
        }
    }
    .done()
    .let { JsonWriter.string(it) }
