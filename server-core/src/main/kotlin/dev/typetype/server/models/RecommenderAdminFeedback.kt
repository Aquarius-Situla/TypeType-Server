package dev.typetype.server.models

import kotlinx.serialization.Serializable

@Serializable
data class RecommenderAdminFeedback(
    val usersEnabled: Int,
    val usersByAlgorithm: Map<String, Int>,
    val verdicts: List<RecommenderVerdictCount>,
    val comments: List<RecommenderFeedbackItem>,
)

@Serializable
data class RecommenderVerdictCount(
    val algorithmId: String,
    val good: Int = 0,
    val bad: Int = 0,
    val notForMe: Int = 0,
)
