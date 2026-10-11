package dev.typetype.server.db

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

object DatabaseRecommenderMigration {
    fun apply() {
        exec("ALTER TABLE admin_settings ADD COLUMN IF NOT EXISTS recommender_enabled BOOLEAN NOT NULL DEFAULT FALSE")
        exec("ALTER TABLE admin_settings ADD COLUMN IF NOT EXISTS recommender_default_on BOOLEAN NOT NULL DEFAULT FALSE")
        exec("ALTER TABLE admin_settings ADD COLUMN IF NOT EXISTS recommender_default_algorithm TEXT")
        exec("ALTER TABLE admin_settings ADD COLUMN IF NOT EXISTS recommender_allowed_algorithms TEXT NOT NULL DEFAULT ''")
        exec("ALTER TABLE admin_settings ADD COLUMN IF NOT EXISTS recommender_timeout_ms INTEGER NOT NULL DEFAULT 0")
        exec(
            "ALTER TABLE admin_settings ADD COLUMN IF NOT EXISTS " +
                "recommender_stream_requests_per_minute INTEGER NOT NULL DEFAULT 0",
        )
        listOf("reason", "title", "channel_name", "thumbnail_url").forEach {
            exec("ALTER TABLE recommender_feedback ADD COLUMN IF NOT EXISTS $it TEXT")
        }
    }

    private fun exec(sql: String) {
        TransactionManager.current().exec(sql)
    }
}
