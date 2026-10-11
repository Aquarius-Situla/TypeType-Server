package dev.typetype.server.services

import dev.typetype.server.models.HistoryItem
import dev.typetype.server.models.RecommenderDataCategory
import dev.typetype.server.models.RecommenderDataItem
import dev.typetype.server.models.RecommenderSettingsItem

private const val SHOWN_ITEMS = 50
private const val VIEWINGS_PER_VIDEO = 4
private const val PERCENT = 100

class RecommenderDataService(
    private val subscriptionsService: SubscriptionsService,
    private val blockedService: BlockedService,
    private val feedbackService: RecommenderFeedbackService,
    private val settingsService: SettingsService,
) {
    suspend fun overview(userId: String, settings: RecommenderSettingsItem): List<RecommenderDataCategory> {
        val excluded = settings.excludedItems.toSet()
        val recording = !settingsService.get(userId).disableWatchHistory
        val subscriptions = subscriptionsService.getAll(userId)
        val blocked = blockedService.getUserChannels(userId) + blockedService.getUserVideos(userId)
        val notForMe = feedbackService.list(userId).filter { it.kind == RecommenderFeedbackService.KIND_VIDEO }
        val likes = RecommenderUserData.likeCount(userId)
        fun category(id: String, items: List<RecommenderDataItem>, count: Int, oldest: Long?, collect: Boolean? = null) =
            RecommenderDataCategory(
                id = id,
                count = count,
                oldestAt = oldest?.takeIf { it > 0L },
                use = id !in settings.unusedData,
                retentionDays = settings.retentionDays[id],
                collect = collect,
                items = items,
            )
        // Counted by the database and only the newest read: an account may hold 700,000 watches.
        suspend fun watches(id: String, shorts: Boolean): RecommenderDataCategory {
            val (count, oldest) = RecommenderUserData.watchCount(userId, shorts)
            val items = RecommenderUserData.newestWatches(userId, shorts, SHOWN_ITEMS * VIEWINGS_PER_VIDEO)
                .groupBy { it.url }.values.take(SHOWN_ITEMS).map { viewings ->
                    val newest = viewings.first()
                    val ref = RecommenderDataFilter.videoRef(newest.url)
                    RecommenderDataItem(
                        id = ref,
                        title = newest.title,
                        channel = newest.channelName,
                        at = newest.watchedAt,
                        watchedPercent = viewings.mapNotNull { it.watchedPercent() }.maxOrNull(),
                        excluded = ref in excluded || viewings.all { RecommenderDataFilter.historyRef(it) in excluded },
                        thumbnailUrl = newest.thumbnail.takeIf { it.isNotBlank() } ?: thumbnailOf(newest.url),
                        viewings = viewings.size,
                    )
                }
            return category(id, items, count, oldest, collect = recording)
        }
        return listOf(
            watches(RecommenderDataFilter.HISTORY, shorts = false),
            watches(RecommenderDataFilter.SHORTS_HISTORY, shorts = true),
            category(
                RecommenderDataFilter.FAVORITES,
                RecommenderUserData.likes(userId, SHOWN_ITEMS).map { item ->
                    val ref = RecommenderDataFilter.favoriteRef(item)
                    RecommenderDataItem(ref, item.title, item.channelName, item.favoritedAt, excluded = ref in excluded)
                },
                likes.first,
                likes.second,
            ),
            category(
                RecommenderDataFilter.SUBSCRIPTIONS,
                subscriptions.sortedByDescending { it.subscribedAt }.take(SHOWN_ITEMS)
                    .map { RecommenderDataItem("subscription:${it.channelUrl}", it.name, at = it.subscribedAt) },
                subscriptions.size,
                subscriptions.minOfOrNull { it.subscribedAt },
            ),
            category(
                RecommenderDataFilter.BLOCKED,
                blocked.sortedByDescending { it.blockedAt }.take(SHOWN_ITEMS)
                    .map { RecommenderDataItem("blocked:${it.url}", it.name ?: it.url, at = it.blockedAt) },
                blocked.size,
                blocked.minOfOrNull { it.blockedAt },
            ),
            category(
                RecommenderDataFilter.NOT_INTERESTED,
                notForMe.take(SHOWN_ITEMS).map { RecommenderDataItem("feedback:${it.id}", it.videoUrl.orEmpty(), at = it.createdAt) },
                notForMe.size,
                notForMe.minOfOrNull { it.createdAt },
            ),
        )
    }

    private fun HistoryItem.watchedPercent(): Int? =
        if (duration > 0L) (progress * PERCENT / duration).toInt().coerceIn(0, PERCENT) else null
}
