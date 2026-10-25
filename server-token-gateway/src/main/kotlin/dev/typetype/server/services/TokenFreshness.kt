package dev.typetype.server.services

import org.json.JSONObject
import java.time.Instant

internal object TokenFreshness {
    fun generation(json: JSONObject): Long? = when (val value = json.opt("generation")) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    fun expiresAtMs(json: JSONObject): Long? = when (val value = json.opt("expiresAt")) {
        is Number -> epochMs(value.toLong())
        is String -> value.toLongOrNull()?.let(::epochMs) ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        else -> null
    }

    private fun epochMs(value: Long): Long? = when {
        value <= 0L -> null
        value < SECONDS_THRESHOLD -> value * 1_000L
        else -> value
    }

    private const val SECONDS_THRESHOLD = 100_000_000_000L
}
