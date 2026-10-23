package dev.typetype.server.services

import dev.typetype.server.sabr.SabrSegmentRequest

internal object SabrVodDemandExpiration {
    fun expireInFlight(
        holder: SabrSessionHolder,
        demand: SabrInFlightDemand,
        nowMs: Long,
        expectedDelayMs: Long,
    ): Boolean = synchronized(holder) {
        val state = holder.playbackState()
        if (state == SabrPlaybackState.TERMINAL || state == SabrPlaybackState.NETWORK_FAILED) return@synchronized false
        if (holder.inFlightSegmentDemand()?.identity != demand.identity) return@synchronized false
        if (holder.session.getCachedSegment(demand.request) != null) return@synchronized false
        val pendingIdentity = holder.segmentDemandIdentity(demand.request)
        if (pendingIdentity != null && pendingIdentity != demand.identity) return@synchronized false
        if (!holder.requestInFlightDemandInterruption(
                demand.identity,
                SabrDemandInterruption(
                    action = SabrDemandInterruptionAction.RESTART,
                    atMs = nowMs,
                    reason = "vod_demand_deadline",
                ),
            )
        ) return@synchronized false
        holder.clearSegmentDemand(demand.request, demand.identity)
        logExpired(
            holder,
            demand.request,
            demand.registeredAtMs,
            nowMs,
            expectedDelayMs,
            demand.attempts(),
            demand.lastAttemptDurationMs(),
        )
        true
    }

    fun expireQueued(
        holder: SabrSessionHolder,
        request: SabrSegmentRequest,
        identity: String,
        nowMs: Long,
        expectedDelayMs: Long,
    ): Boolean = synchronized(holder) {
        val state = holder.playbackState()
        if (state == SabrPlaybackState.TERMINAL || state == SabrPlaybackState.NETWORK_FAILED) return@synchronized false
        if (holder.segmentDemandIdentity(request) != identity) return@synchronized false
        if (holder.session.getCachedSegment(request) != null) return@synchronized false
        val registeredAtMs = holder.segmentDemandRegisteredAtMs(request, identity) ?: return@synchronized false
        if (!holder.clearSegmentDemand(request, identity)) return@synchronized false
        logExpired(holder, request, registeredAtMs, nowMs, expectedDelayMs)
        true
    }

    private fun logExpired(
        holder: SabrSessionHolder,
        request: SabrSegmentRequest,
        registeredAtMs: Long,
        nowMs: Long,
        expectedDelayMs: Long,
        attempts: Int = 0,
        lastAttemptDurationMs: Long = -1L,
    ): Unit = SabrPumpLogger.expired(
        holder,
        request,
        recoverable = true,
        registeredAtMs = registeredAtMs,
        nowMs = nowMs,
        expectedDelayMs = expectedDelayMs,
        reason = "vod_demand_deadline",
        attempts = attempts,
        lastAttemptDurationMs = lastAttemptDurationMs,
        event = "vod_demand_expired",
    )
}
