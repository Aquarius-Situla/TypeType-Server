package dev.typetype.server.services

import kotlinx.serialization.json.JsonObject

sealed interface RecommenderCallResult {
    data class Success(val body: JsonObject) : RecommenderCallResult

    data object Expired : RecommenderCallResult

    data class Failure(val reason: String) : RecommenderCallResult
}
