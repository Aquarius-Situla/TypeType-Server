package dev.typetype.server.services

import dev.typetype.server.models.HistoryItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val EXAMPLES_PER_GROUP = 3
private const val WATCHES_PER_CHANNEL = 1
private const val CHANNEL_PREFIX = "channel:"
private const val HISTORY_PREFIX = "history:"
private const val FAVORITE_PREFIX = "favorite:"

class RecommenderProfileIllustrator(private val subscriptionsService: SubscriptionsService) {
    suspend fun illustrate(userId: String, profile: JsonObject): JsonObject = runCatching {
        val groups = profile["groups"]?.jsonArray.orEmpty()
        val shown = groups.map { group ->
            group.jsonObject["from"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.take(EXAMPLES_PER_GROUP)
        }
        val watchIds = shown.flatten().filter { it.startsWith(HISTORY_PREFIX) }.map { it.removePrefix(HISTORY_PREFIX) }
        val watches = RecommenderUserData.watchesById(userId, watchIds).associateBy { HISTORY_PREFIX + it.id }
        val followed = subscriptionsService.getAll(userId).filter { it.avatarUrl.isNotBlank() }
            .associate { channelKey(it.channelUrl) to it.avatarUrl }
        val channels = profile["channels"]?.jsonArray.orEmpty()
        val channelWatches = channels.associate { channel ->
            channel.jsonObject["id"]?.jsonPrimitive?.content to channel.jsonObject["from"]?.jsonArray.orEmpty()
                .map { it.jsonPrimitive.content }.filter { it.startsWith(HISTORY_PREFIX) }
                .map { it.removePrefix(HISTORY_PREFIX) }.take(WATCHES_PER_CHANNEL)
        }
        val unfollowed = channelWatches.filterKeys { it !in followed }.values.flatten()
        val watched = RecommenderUserData.channelAvatarsByWatchId(userId, unfollowed)
        JsonObject(
            profile + mapOf(
                "groups" to JsonArray(groups.mapIndexed { index, group ->
                    JsonObject(group.jsonObject + ("examples" to examples(shown[index], watches)))
                }),
                "channels" to JsonArray(channels.map { channel ->
                    val id = channel.jsonObject["id"]?.jsonPrimitive?.content
                    val avatar = followed[id] ?: channelWatches[id].orEmpty().firstNotNullOfOrNull { watched[it] }
                    if (avatar == null) channel else JsonObject(channel.jsonObject + ("avatarUrl" to JsonPrimitive(avatar)))
                }),
            ),
        )
    }.getOrDefault(profile)

    private fun examples(refs: List<String>, watches: Map<String, HistoryItem>): JsonElement = buildJsonArray {
        refs.forEach { ref ->
            val watch = watches[ref]
            val url = watch?.url
                ?: ref.takeIf { it.startsWith(FAVORITE_PREFIX) }?.removePrefix(FAVORITE_PREFIX)
                ?: return@forEach
            add(buildJsonObject {
                put("url", url)
                watch?.title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                watch?.channelName?.takeIf { it.isNotBlank() }?.let { put("channel", it) }
                (watch?.thumbnail?.takeIf { it.isNotBlank() } ?: thumbnailOf(url))?.let { put("thumbnailUrl", it) }
            })
        }
    }
}

private fun channelKey(channelUrl: String): String = CHANNEL_PREFIX + channelUrl.trimEnd('/').substringAfterLast('/')

internal fun thumbnailOf(url: String): String? = youtubeVideoId(url)?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }
