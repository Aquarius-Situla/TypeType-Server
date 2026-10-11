package dev.typetype.server.services

import dev.typetype.server.models.HomeRecommendationRow
import dev.typetype.server.models.RecommenderPageTrace
import dev.typetype.server.models.RecommenderTraceRow
import dev.typetype.server.models.RecommenderTraceScore
import dev.typetype.server.models.RecommenderTraceSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_SCORES = 60

object RecommenderTraceReader {
    fun read(
        algorithmId: String,
        ranked: JsonObject,
        rows: List<HomeRecommendationRow>,
        candidates: RecommenderCandidateGroups,
        builtAt: Long,
    ): RecommenderPageTrace? {
        val trace = ranked["trace"] as? JsonObject ?: return null
        val rowOf = rows.flatMap { row -> row.videoUrls.map { it to row.title } }.toMap()
        val why = trace["rows"]?.jsonArray.orEmpty().map { it.jsonObject }
            .associate { it.text("id") to it.text("why") }
        return RecommenderPageTrace(
            algorithm = algorithmId,
            builtAt = builtAt,
            sources = candidates.groups.map { it.jsonObject }.map { group ->
                val source = group["source"]?.jsonObject ?: JsonObject(emptyMap())
                RecommenderTraceSource(
                    kind = source.text("kind"),
                    detail = listOf("query", "channel_url", "video_url", "of").firstNotNullOfOrNull { source.textOrNull(it) }.orEmpty(),
                    received = group["videos"]?.jsonArray?.size ?: 0,
                )
            },
            candidatesReceived = trace["candidates_received"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            dropped = trace["dropped"]?.jsonArray.orEmpty().groupingBy { it.jsonObject.text("why") }.eachCount(),
            shown = rows.sumOf { it.videoUrls.size },
            rows = rows.map { RecommenderTraceRow(it.id, it.title, why[it.id].orEmpty(), it.videoUrls.size) },
            scores = trace["scores"]?.jsonArray.orEmpty().map { it.jsonObject }
                .filter { it.text("url") in rowOf }
                .sortedByDescending { it["total"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }
                .take(MAX_SCORES)
                .map { score ->
                    val url = score.text("url")
                    val video = candidates.videosByUrl[url]
                    RecommenderTraceScore(
                        url = url,
                        title = video?.title.orEmpty(),
                        channel = video?.uploaderName.orEmpty(),
                        row = rowOf[url].orEmpty(),
                        total = score["total"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        factors = score["factors"]?.jsonObject.orEmpty()
                            .mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 },
                    )
                },
        )
    }

    private fun JsonObject.textOrNull(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.text(name: String): String = textOrNull(name).orEmpty()
}
