package dev.typetype.server.services

import dev.typetype.server.models.HomeRecommendationRow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RecommenderPageBuilder(
    private val client: RecommenderClient,
    private val snapshotService: RecommenderSnapshotService,
    private val sourceFetcher: RecommenderSourceFetcher,
    private val metrics: RecommenderMetrics = RecommenderMetrics(),
) {
    suspend fun build(request: RecommenderPageRequest): RecommenderPage? {
        var snapshot = snapshotService.snapshot(request) ?: return null
        val planStarted = System.currentTimeMillis()
        var plan = client.plan(snapshot, request.timeoutMs)
        if (plan is RecommenderCallResult.Expired) {
            snapshot = snapshotService.snapshot(request, resend = true) ?: return null
            plan = client.plan(snapshot, request.timeoutMs)
        }
        val planned = (plan as? RecommenderCallResult.Success)?.body ?: return null
        val sources = planned["sources"]?.jsonArray ?: return null
        val sourcesStarted = System.currentTimeMillis()
        val candidates = sourceFetcher.fetch(request.userId, sources, request.seed)
        val rankStarted = System.currentTimeMillis()
        val ranked = rank(planned, snapshot, candidates, request.timeoutMs) ?: return null
        metrics.sourceMillis.addAndGet(rankStarted - sourcesStarted)
        metrics.recommenderMillis.addAndGet((sourcesStarted - planStarted) + (System.currentTimeMillis() - rankStarted))
        val rows = ranked["rows"]?.jsonArray.orEmpty().map { it.jsonObject }.mapNotNull { it.toRow(candidates) }
        val shown = rows.flatMap { it.videoUrls }.distinct().mapNotNull(candidates.videosByUrl::get)
        val trace = RecommenderTraceReader.read(request.algorithmId, ranked, rows, candidates, System.currentTimeMillis())
        return RecommenderPage(algorithm = request.algorithmId, rows = rows, items = shown, trace = trace)
    }

    private suspend fun rank(
        planned: JsonObject,
        snapshot: JsonObject,
        candidates: RecommenderCandidateGroups,
        timeoutMs: Long,
    ): JsonObject? {
        val planId = planned["plan_id"]?.jsonPrimitive?.contentOrNull
        val byPlan = buildJsonObject {
            if (planId != null) put("plan_id", planId) else put("snapshot", snapshot)
            put("candidates", candidates.groups)
            put("trace", true)
        }
        val first = client.rank(byPlan, timeoutMs)
        if (first is RecommenderCallResult.Success) return first.body
        if (first !is RecommenderCallResult.Expired) return null
        val bySnapshot = buildJsonObject {
            put("snapshot", snapshot)
            put("candidates", candidates.groups)
            put("trace", true)
        }
        return (client.rank(bySnapshot, timeoutMs) as? RecommenderCallResult.Success)?.body
    }

    private fun JsonObject.toRow(candidates: RecommenderCandidateGroups): HomeRecommendationRow? {
        val reason = this["reason"]?.jsonObject
        val urls = this["videos"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }
            .filter { it in candidates.videosByUrl }
        if (urls.isEmpty()) return null
        return HomeRecommendationRow(
            id = this["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            title = this["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            reason = reason?.get("kind")?.jsonPrimitive?.contentOrNull.orEmpty(),
            reasonRef = reason?.get("ref")?.jsonPrimitive?.contentOrNull,
            videoUrls = urls,
        )
    }
}
