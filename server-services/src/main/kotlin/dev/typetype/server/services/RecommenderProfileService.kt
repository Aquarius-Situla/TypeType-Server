package dev.typetype.server.services

import kotlinx.serialization.json.JsonObject

class RecommenderProfileService(
    private val client: RecommenderClient,
    private val snapshotService: RecommenderSnapshotService,
) {
    suspend fun profile(request: RecommenderPageRequest): JsonObject? {
        val snapshot = snapshotService.snapshot(request) ?: return null
        val first = client.profile(snapshot, request.timeoutMs)
        if (first is RecommenderCallResult.Success) return first.body
        if (first !is RecommenderCallResult.Expired) return null
        val again = snapshotService.snapshot(request, resend = true) ?: return null
        return (client.profile(again, request.timeoutMs) as? RecommenderCallResult.Success)?.body
    }
}
