package dev.typetype.server.services

import dev.typetype.server.db.DatabaseFactory
import dev.typetype.server.models.RecommenderFeedbackItem

class RecommenderFeedbackService(
    private val settingsService: RecommenderSettingsService,
    private val defaultAlgorithmId: suspend () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun list(userId: String): List<RecommenderFeedbackItem> =
        DatabaseFactory.query { RecommenderUserDataRepository.feedback(userId) }

    suspend fun add(userId: String, request: RecommenderFeedbackItem): RecommenderFeedbackItem? {
        val videoUrl = request.videoUrl?.trim()?.takeIf { it.isNotEmpty() }
        val valid = when (request.kind) {
            KIND_PAGE -> request.verdict in PAGE_VERDICTS
            KIND_VIDEO -> request.verdict == VERDICT_NOT_FOR_ME && videoUrl != null
            else -> false
        }
        if (!valid) return null
        val video = request.kind == KIND_VIDEO
        val reason = request.reason?.takeIf {
            if (video) it in VIDEO_REASONS else request.verdict == VERDICT_BAD && it in PAGE_REASONS
        }
        val known = if (video) list(userId).firstOrNull { it.kind == KIND_VIDEO && it.videoUrl == videoUrl } else null
        if (known != null) {
            if (reason == null || reason == known.reason) return known
            DatabaseFactory.query { RecommenderUserDataRepository.setFeedbackReason(userId, known.id, reason) }
            return known.copy(reason = reason)
        }
        val settings = settingsService.get(userId)
        val item = RecommenderFeedbackItem(
            kind = request.kind,
            verdict = request.verdict,
            comment = request.comment?.trim()?.take(MAX_COMMENT_CHARS)?.takeIf { it.isNotEmpty() },
            videoUrl = videoUrl?.take(MAX_URL_CHARS).takeIf { video },
            reason = reason,
            title = request.title.shown(MAX_TITLE_CHARS, video),
            channelName = request.channelName.shown(MAX_TITLE_CHARS, video),
            thumbnailUrl = request.thumbnailUrl.shown(MAX_URL_CHARS, video),
            algorithmId = settings.algorithmId ?: defaultAlgorithmId().orEmpty(),
            parameters = settings.parameters,
            createdAt = now(),
        )
        return DatabaseFactory.query { RecommenderUserDataRepository.addFeedback(userId, item) }
    }

    suspend fun delete(userId: String, id: String): RecommenderFeedbackItem? {
        val item = list(userId).firstOrNull { it.id == id } ?: return null
        return item.takeIf { DatabaseFactory.query { RecommenderUserDataRepository.deleteFeedback(userId, id) } }
    }

    private fun String?.shown(max: Int, kept: Boolean): String? =
        this?.trim()?.take(max)?.takeIf { kept && it.isNotEmpty() }

    suspend fun notForMeUrls(userId: String): List<String> =
        list(userId).filter { it.kind == KIND_VIDEO }.mapNotNull { it.videoUrl }.distinct()

    companion object {
        const val KIND_PAGE = "page"
        const val KIND_VIDEO = "video"
        const val VERDICT_NOT_FOR_ME = "not_for_me"
        const val VERDICT_BAD = "bad"
        val PAGE_VERDICTS: Set<String> = setOf("good", VERDICT_BAD)
        val PAGE_REASONS: Set<String> = setOf("repetitive", "unrelated", "old", "missing")
        val VIDEO_REASONS: Set<String> = setOf("seen", "channel", "topic", "other")
        private const val MAX_TITLE_CHARS = 300
        private const val MAX_COMMENT_CHARS = 1_000
        private const val MAX_URL_CHARS = 500
    }
}
