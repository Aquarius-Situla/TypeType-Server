package dev.typetype.server.models

import kotlinx.serialization.Serializable

@Serializable
data class RecommenderAdminStatus(
    val installed: Boolean,
    val enabled: Boolean,
    val reachable: Boolean,
    val healthMs: Long? = null,
    val algorithms: List<RecommenderAlgorithmName> = emptyList(),
    val defaultAlgorithm: String? = null,
    val pagesBuilt: Long = 0,
    val pagesFromCache: Long = 0,
    val fallbacks: Long = 0,
    val averageBuildMs: Long = 0,
    val averageRecommenderMs: Long = 0,
    val averageSourcesMs: Long = 0,
    val buildMsTotal: Long = 0,
    val recommenderMsTotal: Long = 0,
    val sourcesMsTotal: Long = 0,
    val sourceRequests: Long = 0,
    val sourceCacheHits: Long = 0,
    val streamRequests: Long = 0,
    val streamRequestsRefused: Long = 0,
    val streamRequestsThisMinute: Long = 0,
    val streamRequestsPerMinuteLimit: Int = 0,
    val streamRequestsPaused: Boolean = false,
    val requestTimeoutMs: Long = 0,
)

@Serializable
data class RecommenderAlgorithmName(val id: String, val name: String)
