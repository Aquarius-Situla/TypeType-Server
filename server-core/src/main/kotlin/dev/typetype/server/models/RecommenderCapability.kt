package dev.typetype.server.models

import kotlinx.serialization.Serializable

@Serializable
data class RecommenderCapability(
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val experimental: Boolean = true,
)
