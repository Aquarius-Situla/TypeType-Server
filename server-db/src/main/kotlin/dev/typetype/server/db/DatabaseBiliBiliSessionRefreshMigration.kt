package dev.typetype.server.db

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

object DatabaseBiliBiliSessionRefreshMigration {
    fun apply() {
        exec("ALTER TABLE bilibili_sessions ADD COLUMN IF NOT EXISTS encrypted_refresh_token TEXT")
        exec("ALTER TABLE bilibili_sessions ADD COLUMN IF NOT EXISTS encrypted_pending_confirm_token TEXT")
        exec("ALTER TABLE bilibili_sessions ADD COLUMN IF NOT EXISTS refresh_checked_at BIGINT NOT NULL DEFAULT 0")
        exec("ALTER TABLE bilibili_sessions ADD COLUMN IF NOT EXISTS refresh_claimed_until BIGINT NOT NULL DEFAULT 0")
    }

    private fun exec(sql: String) {
        TransactionManager.current().exec(sql)
    }
}
