package dev.typetype.server.models

import kotlinx.serialization.Serializable

@Serializable
data class YoutubeSessionStatusResponse(
    val status: String,
    val updatedAt: Long,
    val lastUsedAt: Long,
    val authType: String = "cookies",
    val accountLabel: String = "",
)
