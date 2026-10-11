package dev.typetype.server.models

import kotlinx.serialization.Serializable

@Serializable
data class RecommenderPageTrace(
    val algorithm: String,
    val builtAt: Long,
    val sources: List<RecommenderTraceSource> = emptyList(),
    val candidatesReceived: Int = 0,
    val dropped: Map<String, Int> = emptyMap(),
    val shown: Int = 0,
    val rows: List<RecommenderTraceRow> = emptyList(),
    val scores: List<RecommenderTraceScore> = emptyList(),
)

@Serializable
data class RecommenderTraceSource(val kind: String, val detail: String, val received: Int)

@Serializable
data class RecommenderTraceRow(val id: String, val title: String, val why: String, val videos: Int)

@Serializable
data class RecommenderTraceScore(
    val url: String,
    val title: String,
    val channel: String,
    val row: String,
    val total: Double,
    val factors: Map<String, Double>,
)
