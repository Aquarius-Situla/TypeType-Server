package dev.typetype.server.portability

import dev.typetype.server.services.ChannelUrlCanonicalizer

/**
 * When a Takeout lists the current subscriptions (subscriptions.csv), that list decides which
 * channels are subscribed. "Subscribed to" activity entries stay after an unsubscribe, so they
 * only tell since when a listed channel is subscribed.
 */
internal object YoutubeTakeoutSubscriptionDates {
    private const val NAMESPACE = "youtube-takeout-subscribed-at"

    fun remember(sink: PortabilityRecordSink, channelUrl: String, subscribedAt: Long) {
        if (subscribedAt <= 0L || channelUrl.isBlank()) return
        val key = ChannelUrlCanonicalizer.canonicalize(channelUrl)
        val known = sink.lookup(NAMESPACE, key)?.toLongOrNull() ?: 0L
        // After an unsubscribe and a new subscribe, the current subscription starts at the latest.
        if (subscribedAt > known) sink.putLookup(NAMESPACE, key, subscribedAt.toString())
    }

    fun of(sink: PortabilityRecordSink, channelUrl: String): Long {
        if (channelUrl.isBlank()) return 0L
        return sink.lookup(NAMESPACE, ChannelUrlCanonicalizer.canonicalize(channelUrl))?.toLongOrNull() ?: 0L
    }
}
