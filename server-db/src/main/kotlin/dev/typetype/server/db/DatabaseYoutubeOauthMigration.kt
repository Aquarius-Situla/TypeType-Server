package dev.typetype.server.db

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

object DatabaseYoutubeOauthMigration {
    fun apply() {
        exec("ALTER TABLE youtube_sessions ADD COLUMN IF NOT EXISTS auth_type TEXT NOT NULL DEFAULT 'cookies'")
        exec("ALTER TABLE youtube_sessions ADD COLUMN IF NOT EXISTS encrypted_access_token TEXT NOT NULL DEFAULT ''")
        exec("ALTER TABLE youtube_sessions ADD COLUMN IF NOT EXISTS encrypted_refresh_token TEXT NOT NULL DEFAULT ''")
        exec("ALTER TABLE youtube_sessions ADD COLUMN IF NOT EXISTS access_expires_at BIGINT NOT NULL DEFAULT 0")
        exec("ALTER TABLE youtube_sessions ADD COLUMN IF NOT EXISTS account_label TEXT NOT NULL DEFAULT ''")
    }

    private fun exec(sql: String) {
        TransactionManager.current().exec(sql)
    }
}
