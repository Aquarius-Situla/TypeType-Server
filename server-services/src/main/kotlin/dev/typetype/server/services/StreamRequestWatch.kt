package dev.typetype.server.services

import dev.typetype.server.models.ExtractionFailureKind
import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.models.StreamResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class StreamRequestWatch {
    @Volatile
    var listener: Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun refused() {
        val listener = listener ?: return
        scope.launch { runCatching { listener.opened(alreadyCounted = true, blocked = true) } }
    }

    suspend fun opened(result: ExtractionResult<StreamResponse>) {
        val listener = listener ?: return
        val blocked = (result as? ExtractionResult.Failure)?.kind == ExtractionFailureKind.ProviderAccessBlocked
        val counted = coroutineContext[AlreadyCounted] != null
        runCatching { listener.opened(counted, blocked) }
    }

    suspend fun paused(): Boolean = runCatching { listener?.paused() }.getOrNull() ?: false

    suspend fun admit(): Boolean = runCatching { listener?.admit() }.getOrNull() ?: true

    interface Listener {
        suspend fun opened(alreadyCounted: Boolean, blocked: Boolean)

        suspend fun paused(): Boolean = false

        suspend fun admit(): Boolean = true
    }

    class NobodyWaiting private constructor() : AbstractCoroutineContextElement(NobodyWaiting) {
        companion object Key : CoroutineContext.Key<NobodyWaiting> {
            val mark = NobodyWaiting()
        }
    }

    class AlreadyCounted private constructor() : AbstractCoroutineContextElement(AlreadyCounted) {
        companion object Key : CoroutineContext.Key<AlreadyCounted> {
            val mark = AlreadyCounted()
        }
    }
}
