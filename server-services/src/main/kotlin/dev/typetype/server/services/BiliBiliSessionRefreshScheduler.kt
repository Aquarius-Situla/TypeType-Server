package dev.typetype.server.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class BiliBiliSessionRefreshScheduler(
    private val service: BiliBiliSessionService,
    private val initialDelayMs: Long = 60_000L,
    private val intervalMs: Long = 60 * 60 * 1_000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (!service.isConfigured || job?.isActive == true) return
        job = scope.launch {
            delay(initialDelayMs)
            while (isActive) {
                try {
                    service.refreshDueSessions()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logger.warn("BiliBili session refresh cycle failed (${error::class.simpleName})")
                }
                delay(intervalMs)
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    private companion object {
        val logger = LoggerFactory.getLogger(BiliBiliSessionRefreshScheduler::class.java)
    }
}
