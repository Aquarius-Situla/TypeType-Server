package dev.typetype.server.models

import kotlinx.serialization.Serializable

@Serializable
data class YoutubeDeviceStartResponse(
    val userCode: String,
    val verificationUrl: String,
    val expiresAt: Long,
    val intervalSeconds: Int,
)

@Serializable
data class YoutubeDevicePollResponse(
    val status: String,
)
