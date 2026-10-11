package dev.typetype.server.services

import kotlinx.serialization.json.JsonObject

class RecommenderSnapshotService(
    private val historyService: RecommenderHistoryService,
    private val subscriptionsService: SubscriptionsService,
    private val blockedService: BlockedService,
    private val notForMeUrls: suspend (userId: String) -> List<String>,
    private val algorithmDataUsedBy: suspend (locale: String, algorithmId: String) -> Set<String>?,
) {
    suspend fun snapshot(request: RecommenderPageRequest, resend: Boolean = false): JsonObject? {
        val nowMs = System.currentTimeMillis()
        val algorithmUses = algorithmDataUsedBy(request.locale, request.algorithmId) ?: return null
        val filter = RecommenderDataFilter(request.settings, nowMs, algorithmUses)
        val historyKey = if (filter.needsHistorySnapshot()) {
            historyService.ensureSent(request.userId, request.timeoutMs, filter, force = resend) ?: return null
        } else {
            null
        }
        val blocked = filter.uses(RecommenderDataFilter.BLOCKED)
        val notForMe = if (filter.uses(RecommenderDataFilter.NOT_INTERESTED)) notForMeUrls(request.userId) else emptyList()
        return RecommenderSnapshotBuilder.snapshot(
            RecommenderSnapshotRequest(
                algorithmId = request.algorithmId,
                settings = request.settings,
                historyKey = historyKey,
                subscriptions = if (filter.uses(RecommenderDataFilter.SUBSCRIPTIONS)) {
                    subscriptionsService.getAll(request.userId)
                } else {
                    emptyList()
                },
                notInterestedUrls = (
                    (if (blocked) blockedService.getVideos(request.userId).map { it.url } else emptyList()) + notForMe
                    ).distinct(),
                mutedChannelUrls = if (blocked) blockedService.getChannels(request.userId).map { it.url } else emptyList(),
                nowMs = nowMs,
                timezone = request.timezone,
                locale = request.locale,
                seed = request.seed,
            ),
        )
    }
}
