package dev.typetype.server.db.tables

import org.jetbrains.exposed.v1.core.Table

object RecommenderFeedbackTable : Table("recommender_feedback") {
    val id = text("id")
    val userId = text("user_id").index()
    val kind = text("kind")
    val verdict = text("verdict")
    val comment = text("comment").nullable()
    val videoUrl = text("video_url").nullable()
    val reason = text("reason").nullable()
    val title = text("title").nullable()
    val channelName = text("channel_name").nullable()
    val thumbnailUrl = text("thumbnail_url").nullable()
    val algorithmId = text("algorithm_id")
    val parametersJson = text("parameters_json").default("{}")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}
