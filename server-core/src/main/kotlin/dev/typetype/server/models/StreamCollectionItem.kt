package dev.typetype.server.models

import kotlinx.serialization.Serializable

@Serializable
data class StreamCollectionItem(
    val id: String,
    val title: String,
    val sections: List<StreamCollectionSectionItem>,
)

@Serializable
data class StreamCollectionSectionItem(
    val id: String,
    val title: String,
    val episodes: List<StreamCollectionEpisodeItem>,
)

@Serializable
data class StreamCollectionEpisodeItem(
    val videoId: String,
    val title: String,
    val url: String,
)
