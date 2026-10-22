package dev.typetype.server.services

import dev.typetype.server.db.DatabaseFactory
import dev.typetype.server.db.tables.PushNotificationDeliveriesTable
import dev.typetype.server.db.tables.PushNotificationEventsTable
import dev.typetype.server.db.tables.PushNotificationSeenVideosTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

class PushNotificationDeliveryStore(
    private val clock: () -> Long,
) {
    fun markSeen(userId: String, candidate: PushCandidate, now: Long): Boolean =
        PushNotificationSeenVideosTable.insertIgnore {
            it[PushNotificationSeenVideosTable.userId] = userId
            it[PushNotificationSeenVideosTable.serviceId] = candidate.serviceId
            it[PushNotificationSeenVideosTable.channelId] = candidate.channelId
            it[PushNotificationSeenVideosTable.videoId] = candidate.videoId
            it[PushNotificationSeenVideosTable.firstSeenAt] = now
        }.insertedCount == 1

    fun claim(eventId: String, userId: String, deviceId: String, createIfMissing: Boolean, now: Long): Boolean {
        val existing = PushNotificationDeliveriesTable.selectAll().where {
            (PushNotificationDeliveriesTable.eventId eq eventId) and
                (PushNotificationDeliveriesTable.userId eq userId) and
                (PushNotificationDeliveriesTable.deviceId eq deviceId)
        }.singleOrNull()
        if (existing == null) {
            if (!createIfMissing) return false
            return PushNotificationDeliveriesTable.insertIgnore {
                it[PushNotificationDeliveriesTable.eventId] = eventId
                it[PushNotificationDeliveriesTable.userId] = userId
                it[PushNotificationDeliveriesTable.deviceId] = deviceId
                it[PushNotificationDeliveriesTable.status] = PushNotificationDeliveryStatus.PENDING
                it[PushNotificationDeliveriesTable.attempts] = 1
                it[PushNotificationDeliveriesTable.updatedAt] = now
            }.insertedCount == 1
        }
        val attempts = existing[PushNotificationDeliveriesTable.attempts]
        val status = existing[PushNotificationDeliveriesTable.status]
        if (status !in PushNotificationDeliveryStatus.RETRYABLE || attempts >= MAX_ATTEMPTS ||
            existing[PushNotificationDeliveriesTable.updatedAt] > now - RETRY_DELAY_MS
        ) return false
        return PushNotificationDeliveriesTable.update({
            (PushNotificationDeliveriesTable.eventId eq eventId) and
                (PushNotificationDeliveriesTable.userId eq userId) and
                (PushNotificationDeliveriesTable.deviceId eq deviceId) and
                (PushNotificationDeliveriesTable.status inList PushNotificationDeliveryStatus.RETRYABLE) and
                (PushNotificationDeliveriesTable.attempts eq attempts) and
                (PushNotificationDeliveriesTable.updatedAt eq existing[PushNotificationDeliveriesTable.updatedAt])
        }) {
            it[PushNotificationDeliveriesTable.status] = PushNotificationDeliveryStatus.PENDING
            it[PushNotificationDeliveriesTable.attempts] = attempts + 1
            it[PushNotificationDeliveriesTable.lastError] = null
            it[PushNotificationDeliveriesTable.updatedAt] = now
        } == 1
    }

    suspend fun update(work: DeliveryWork, status: String, error: String?) {
        DatabaseFactory.query {
            PushNotificationDeliveriesTable.update({
                (PushNotificationDeliveriesTable.eventId eq work.eventId) and
                    (PushNotificationDeliveriesTable.userId eq work.device.userId) and
                    (PushNotificationDeliveriesTable.deviceId eq work.device.deviceId) and
                    (PushNotificationDeliveriesTable.status eq PushNotificationDeliveryStatus.PENDING)
            }) {
                it[PushNotificationDeliveriesTable.status] = status
                it[PushNotificationDeliveriesTable.lastError] = error
                it[PushNotificationDeliveriesTable.updatedAt] = clock()
            }
        }
    }

    suspend fun acknowledge(
        eventId: String,
        userId: String,
        deviceId: String,
        outcome: String,
        reasonCode: String?,
    ): PushDeliveryAcknowledgementResult = DatabaseFactory.query {
        val delivery = PushNotificationDeliveriesTable.selectAll().where {
            (PushNotificationDeliveriesTable.eventId eq eventId) and
                (PushNotificationDeliveriesTable.userId eq userId) and
                (PushNotificationDeliveriesTable.deviceId eq deviceId)
        }.singleOrNull() ?: return@query PushDeliveryAcknowledgementResult.NotFound
        val status = PushNotificationDeliveryStatus.forOutcome(outcome)
        if (delivery[PushNotificationDeliveriesTable.status] == status) {
            return@query PushDeliveryAcknowledgementResult.AlreadyRecorded
        }
        if (delivery[PushNotificationDeliveriesTable.status] !in PushNotificationDeliveryStatus.ACKABLE) {
            return@query PushDeliveryAcknowledgementResult.Conflict
        }
        val error = reasonCode?.let { "client_$it" }
        val updated = PushNotificationDeliveriesTable.update({
            (PushNotificationDeliveriesTable.eventId eq eventId) and
                (PushNotificationDeliveriesTable.userId eq userId) and
                (PushNotificationDeliveriesTable.deviceId eq deviceId) and
                (PushNotificationDeliveriesTable.status inList PushNotificationDeliveryStatus.ACKABLE)
        }) {
            it[PushNotificationDeliveriesTable.status] = status
            it[PushNotificationDeliveriesTable.lastError] = error
            it[PushNotificationDeliveriesTable.updatedAt] = clock()
        }
        if (updated == 1) PushDeliveryAcknowledgementResult.Recorded
        else PushDeliveryAcknowledgementResult.Conflict
    }

    suspend fun clearPending(userId: String, channelId: String) {
        val eventIds = DatabaseFactory.query {
            PushNotificationEventsTable.selectAll().where { PushNotificationEventsTable.channelId eq channelId }
                .mapTo(mutableSetOf()) { it[PushNotificationEventsTable.eventId] }
        }
        if (eventIds.isEmpty()) return
        DatabaseFactory.query {
            PushNotificationDeliveriesTable.deleteWhere {
                (PushNotificationDeliveriesTable.userId eq userId) and
                    (PushNotificationDeliveriesTable.eventId inList eventIds) and
                    (PushNotificationDeliveriesTable.status inList PushNotificationDeliveryStatus.RETRYABLE)
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 30_000L
    }
}

object PushNotificationDeliveryStatus {
    const val PENDING = "pending"
    const val DELIVERED = "delivered"
    const val INVALID = "invalid"
    const val FAILED = "failed"
    const val AWAITING_ACK = "awaiting_ack"
    const val CLIENT_POSTED = "client_posted"
    const val CLIENT_SUPPRESSED = "client_suppressed"
    val ACKABLE = listOf(PENDING, FAILED, AWAITING_ACK)
    val RETRYABLE = listOf(PENDING, FAILED, AWAITING_ACK)

    fun forOutcome(outcome: String): String = when (outcome) {
        "posted" -> CLIENT_POSTED
        "suppressed" -> CLIENT_SUPPRESSED
        "failed" -> FAILED
        else -> error("Unsupported push acknowledgement outcome")
    }
}

enum class PushDeliveryAcknowledgementResult {
    Recorded,
    AlreadyRecorded,
    InvalidRequest,
    NotFound,
    Conflict,
}
