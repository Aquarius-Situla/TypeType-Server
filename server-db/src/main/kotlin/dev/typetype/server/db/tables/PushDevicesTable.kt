package dev.typetype.server.db.tables

import org.jetbrains.exposed.v1.core.Table

object PushDevicesTable : Table("push_devices") {
    val id = text("id")
    val userId = text("user_id")
    val deviceId = text("device_id")
    val platform = text("platform")
    val endpoint = text("endpoint")
    val endpointHash = text("endpoint_hash").uniqueIndex()
    val p256dh = text("p256dh").nullable()
    val authSecret = text("auth_secret").nullable()
    val expiresAt = long("expires_at").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    init {
        index(false, userId)
        uniqueIndex(userId, deviceId)
    }

    override val primaryKey = PrimaryKey(id)
}
