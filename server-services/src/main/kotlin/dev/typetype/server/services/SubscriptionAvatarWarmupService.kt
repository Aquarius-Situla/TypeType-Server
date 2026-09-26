package dev.typetype.server.services

import dev.typetype.server.db.DatabaseFactory
import dev.typetype.server.db.tables.SubscriptionsTable
import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.models.SubscriptionItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class SubscriptionAvatarWarmupService(private val channelService: ChannelService) {
    private val logger = LoggerFactory.getLogger(SubscriptionAvatarWarmupService::class.java)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val recentlyFailed = ConcurrentHashMap<String, Long>()

    fun schedule(scope: CoroutineScope, userId: String, items: List<SubscriptionItem>) {
        val now = System.currentTimeMillis()
        val candidates = items.asSequence()
            .filter { it.avatarUrl.isBlank() }
            .map(SubscriptionItem::channelUrl)
            .distinct()
            .filter { url ->
                val failedAt = recentlyFailed[url]
                failedAt == null || now - failedAt >= FAILURE_COOLDOWN_MS
            }
            .filter(inFlight::add)
            .take(MAX_AVATARS_PER_REQUEST)
            .toList()
        if (candidates.isEmpty()) return
        scope.launch {
            try {
                warm(userId, candidates)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.warn("Background subscription avatar warmup failed", error)
            } finally {
                inFlight.removeAll(candidates.toSet())
            }
        }
    }

    internal suspend fun warm(userId: String, channelUrls: List<String>) {
        channelUrls.chunked(MAX_CONCURRENT_LOOKUPS).forEach { chunk ->
            coroutineScope {
                chunk.map { channelUrl ->
                    async { resolve(userId, channelUrl) }
                }.awaitAll()
            }
            delay(BATCH_DELAY_MS)
        }
    }

    private suspend fun resolve(userId: String, channelUrl: String) {
        when (val result = channelService.getChannel(url = channelUrl, nextpage = null)) {
            is ExtractionResult.Success -> {
                val avatarUrl = result.data.avatarUrl.trim()
                if (avatarUrl.isBlank()) {
                    recentlyFailed[channelUrl] = System.currentTimeMillis()
                    return
                }
                DatabaseFactory.query {
                    SubscriptionsTable.update({
                        (SubscriptionsTable.userId eq userId) and
                            (SubscriptionsTable.channelUrl eq channelUrl) and
                            (SubscriptionsTable.avatarUrl eq "")
                    }) {
                        it[SubscriptionsTable.avatarUrl] = avatarUrl
                    }
                }
                recentlyFailed.remove(channelUrl)
            }
            is ExtractionResult.BadRequest,
            is ExtractionResult.Failure -> recentlyFailed[channelUrl] = System.currentTimeMillis()
        }
    }

    private companion object {
        const val MAX_AVATARS_PER_REQUEST = 8
        const val MAX_CONCURRENT_LOOKUPS = 2
        const val BATCH_DELAY_MS = 750L
        const val FAILURE_COOLDOWN_MS = 15 * 60 * 1000L
    }
}
