package dev.typetype.server.services

import dev.typetype.server.models.VideoItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

private const val FEED_KIND = "subscription_feed"

class RecommenderSourceFetcher(
    private val loader: RecommenderSourceLoader,
    private val subscriptionFeed: suspend (userId: String) -> List<VideoItem>,
    private val knownVideos: suspend (userId: String, urls: List<String>) -> List<VideoItem>,
    private val config: RecommenderConfig,
) {
    suspend fun fetch(userId: String, sources: JsonArray, seed: Int): RecommenderCandidateGroups = coroutineScope {
        val gate = Semaphore(config.maxSourceParallelism)
        val plan = sources.map { it.jsonObject }
        val direct = plan.map { source ->
            if (source.text("of") != null) null
            else async { gate.withPermit { runCatching { load(userId, source) }.getOrDefault(emptyList()) } }
        }.map { it?.await() }
        val feed = plan.indices.firstOrNull { plan[it].text("kind") == FEED_KIND }?.let { direct[it] }.orEmpty()
        val results = plan.mapIndexed { index, source ->
            direct[index] ?: runCatching { relatedOf(feed, source, seed, gate) }.getOrDefault(emptyList())
        }
        RecommenderCandidateGroups(
            groups = buildJsonArray {
                plan.forEachIndexed { index, source ->
                    add(buildJsonObject {
                        put("source", source)
                        put("videos", JsonArray(results[index].map { it.toRecommenderCandidate() }))
                    })
                }
            },
            videosByUrl = results.flatten().associateBy { it.url },
        )
    }

    private suspend fun load(userId: String, source: JsonObject): List<VideoItem> {
        val limit = source["limit"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
        val videos = when (source.text("kind")) {
            "search" -> source.text("query")?.let { loader.search(it) }
            "related" -> source.text("video_url")?.let { loader.related(it) }
            "channel_videos" -> source.text("channel_url")?.let { loader.channelVideos(it, source.text("sort")) }
            FEED_KIND -> subscriptionFeed(userId)
            "trending" -> loader.trending()
            "videos" -> knownVideos(userId, source["urls"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty())
            else -> null
        }
        val now = System.currentTimeMillis()
        return videos.orEmpty().filterNot { it.startsLater(now) }.take(limit)
    }

    private suspend fun relatedOf(
        feed: List<VideoItem>,
        source: JsonObject,
        seed: Int,
        gate: Semaphore,
    ): List<VideoItem> = coroutineScope {
        val count = source["count"]?.jsonPrimitive?.intOrNull ?: 1
        val among = source["among"]?.jsonPrimitive?.intOrNull
        val limit = source["limit"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
        val starts = if (among == null) feed.take(count) else feed.take(among).shuffled(Random(seed)).take(count)
        val now = System.currentTimeMillis()
        starts.map { start ->
            async { gate.withPermit { loader.related(start.url).filterNot { it.startsLater(now) }.take(limit) } }
        }
            .awaitAll()
            .flatten()
            .distinctBy { it.url }
    }

    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}
