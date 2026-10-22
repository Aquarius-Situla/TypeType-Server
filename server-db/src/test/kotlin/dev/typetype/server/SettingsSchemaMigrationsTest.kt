package dev.typetype.server

import dev.typetype.server.db.SettingsSchemaMigrations
import dev.typetype.server.db.tables.SettingsTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SettingsSchemaMigrationsTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initDb() = TestDatabase.setup()
    }

    @BeforeEach
    fun clean() = TestDatabase.truncateAll()

    @Test
    fun `keeps autoplay on open when autoplay next video is disabled`() {
        transaction {
            SettingsTable.insert {
                it[userId] = "settings-user"
                it[autoplay] = false
                it[autoplayOnOpen] = true
            }

            SettingsSchemaMigrations.apply()

            val settings = SettingsTable.selectAll().single()
            assertFalse(settings[SettingsTable.autoplay])
            assertTrue(settings[SettingsTable.autoplayOnOpen])
        }
    }
}
