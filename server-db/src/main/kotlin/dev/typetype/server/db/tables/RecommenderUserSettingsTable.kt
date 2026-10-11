package dev.typetype.server.db.tables

import org.jetbrains.exposed.v1.core.Table

object RecommenderUserSettingsTable : Table("recommender_user_settings") {
    val userId = text("user_id")
    val enabled = bool("enabled").default(false)
    val algorithmId = text("algorithm_id").nullable()
    val settingsJson = text("settings_json").default("{}")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(userId)
}
