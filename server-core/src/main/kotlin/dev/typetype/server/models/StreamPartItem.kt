package dev.typetype.server.models

import kotlinx.serialization.Serializable

@Serializable
data class StreamPartItem(
    val page: Int,
    val title: String,
    val url: String,
    val thumbnailUrl: String,
    val duration: Long,
)
