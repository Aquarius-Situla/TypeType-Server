package dev.typetype.server.services

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ProviderSessionEpoch {
    private val epoch = AtomicLong(0L)
    private val lastGeneration = AtomicLong(UNKNOWN_GENERATION)
    private val lastVisitorData = AtomicReference<String?>(null)

    fun current(): Long = epoch.get()

    fun advance(): Long = epoch.incrementAndGet()

    fun observe(token: SabrTokenBundle, renewed: Boolean = false): Unit {
        if (renewed || changed(token)) advance()
    }

    private fun changed(token: SabrTokenBundle): Boolean {
        val previousVisitor = lastVisitorData.getAndSet(token.visitorData)
        val generation = token.generation
            ?: return previousVisitor != null && previousVisitor != token.visitorData
        val previous = lastGeneration.getAndAccumulate(generation, ::maxOf)
        return previous != UNKNOWN_GENERATION && generation > previous
    }

    private companion object {
        const val UNKNOWN_GENERATION = Long.MIN_VALUE
    }
}
