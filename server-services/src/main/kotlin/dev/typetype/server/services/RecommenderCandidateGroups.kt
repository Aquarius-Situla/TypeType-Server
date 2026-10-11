package dev.typetype.server.services

import dev.typetype.server.models.VideoItem
import kotlinx.serialization.json.JsonArray

data class RecommenderCandidateGroups(
    val groups: JsonArray,
    val videosByUrl: Map<String, VideoItem>,
)
