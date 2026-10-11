package dev.typetype.server.services

import dev.typetype.server.db.DatabaseFactory
import dev.typetype.server.models.RecommenderAdminFeedback
import dev.typetype.server.models.RecommenderAdminStatus
import dev.typetype.server.models.RecommenderVerdictCount

class RecommenderAdminService(
    private val config: RecommenderConfig,
    private val client: RecommenderClient,
    private val catalog: RecommenderAlgorithmCatalog,
    private val metrics: RecommenderMetrics,
    private val streamLimiter: RecommenderStreamLimiter,
    private val adminSettingsService: AdminSettingsService,
) {
    suspend fun status(locale: String): RecommenderAdminStatus {
        val admin = adminSettingsService.get()
        if (!config.installed) return RecommenderAdminStatus(installed = false, enabled = false, reachable = false)
        val started = System.currentTimeMillis()
        val reachable = client.health()
        val built = metrics.pagesBuilt.get()
        return RecommenderAdminStatus(
            installed = true,
            enabled = admin.recommenderEnabled,
            reachable = reachable,
            healthMs = (System.currentTimeMillis() - started).takeIf { reachable },
            algorithms = if (reachable) catalog.allNames(locale) else emptyList(),
            defaultAlgorithm = if (reachable) catalog.defaultId(locale) else null,
            pagesBuilt = built,
            pagesFromCache = metrics.pagesFromCache.get(),
            fallbacks = metrics.fallbacks.get(),
            averageBuildMs = if (built > 0) metrics.buildMillis.get() / built else 0,
            averageRecommenderMs = if (built > 0) metrics.recommenderMillis.get() / built else 0,
            averageSourcesMs = if (built > 0) metrics.sourceMillis.get() / built else 0,
            buildMsTotal = metrics.buildMillis.get(),
            recommenderMsTotal = metrics.recommenderMillis.get(),
            sourcesMsTotal = metrics.sourceMillis.get(),
            sourceRequests = metrics.sourceRequests.get(),
            sourceCacheHits = metrics.sourceCacheHits.get(),
            streamRequests = metrics.streamRequests.get(),
            streamRequestsRefused = metrics.streamRequestsRefused.get(),
            streamRequestsThisMinute = streamLimiter.usedThisMinute(),
            streamRequestsPaused = streamLimiter.walled(),
            streamRequestsPerMinuteLimit = admin.recommenderStreamRequestsPerMinute.takeIf { it > 0 }
                ?: config.streamRequestsPerMinute,
            requestTimeoutMs = admin.recommenderTimeoutMs.takeIf { it > 0 }?.toLong() ?: config.requestTimeoutMs,
        )
    }

    suspend fun feedback(): RecommenderAdminFeedback {
        val (users, opinions) = DatabaseFactory.query {
            RecommenderUserDataRepository.enabledUsersByAlgorithm() to
                RecommenderUserDataRepository.allFeedback(MAX_OPINIONS_READ)
        }
        val verdicts = opinions.groupBy { it.algorithmId }.map { (algorithmId, items) ->
            RecommenderVerdictCount(
                algorithmId = algorithmId,
                good = items.count { it.verdict == "good" },
                bad = items.count { it.verdict == "bad" },
                notForMe = items.count { it.verdict == RecommenderFeedbackService.VERDICT_NOT_FOR_ME },
            )
        }
        return RecommenderAdminFeedback(
            usersEnabled = users.values.sum(),
            usersByAlgorithm = users,
            verdicts = verdicts.sortedBy { it.algorithmId },
            comments = opinions.filter { !it.comment.isNullOrBlank() }.take(MAX_COMMENTS_SHOWN).map { it.copy(id = "") },
        )
    }

    private companion object {
        const val MAX_OPINIONS_READ = 5_000
        const val MAX_COMMENTS_SHOWN = 100
    }
}
