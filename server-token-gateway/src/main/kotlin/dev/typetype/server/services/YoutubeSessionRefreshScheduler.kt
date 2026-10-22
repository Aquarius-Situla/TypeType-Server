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

class YoutubeSessionRefreshScheduler(
    private val service: YoutubeSessionRefreshService,
    private val initialDelayMs: Long = 10 * 60 * 1_000L,
    private val intervalMs: Long = 6 * 60 * 60 * 1_000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (!service.isConfigured || job?.isActive == true) return
        job = scope.launch {
            delay(initialDelayMs)
            while (isActive) {
                try {
                    service.refreshAll()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logger.warn("YouTube session refresh cycle failed (${error::class.simpleName})")
                }
                delay(intervalMs)
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    private companion object {
        val logger = LoggerFactory.getLogger(YoutubeSessionRefreshScheduler::class.java)
    }
}
