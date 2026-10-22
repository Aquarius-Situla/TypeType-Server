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
    .value("clientScreen", "WATCH")
    .value("userAgent", YOUTUBE_TV_USER_AGENT)
    .value("acceptLanguage", "en-US")
    .value("acceptRegion", "US")
    .value("utcOffsetMinutes", "0")
    .let { builder ->
        if (visitorData.isNullOrBlank()) builder else builder.value("visitorData", visitorData)
    }
    .apply {
        if (!livingRoomPoTokenId.isNullOrBlank()) {
            `object`("tvAppInfo").value("livingRoomPoTokenId", livingRoomPoTokenId).end()
        }
    }
    .end()
    .`object`("user")
    .value("enableSafetyMode", false)
    .value("lockedSafetyMode", false)
    .end()
    .end()
    .`object`("playbackContext")
    .`object`("contentPlaybackContext")
    .value("html5Preference", "HTML5_PREF_WANTS")
    .value("lactMilliseconds", 60_000)
    .value("isInlinePlaybackNoAd", true)
    .value("signatureTimestamp", signatureTimestamp)
    .end()
    .`object`("devicePlaybackCapabilities")
    .value("supportsVp9Encoding", true)
    .value("supportXhr", false)
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
