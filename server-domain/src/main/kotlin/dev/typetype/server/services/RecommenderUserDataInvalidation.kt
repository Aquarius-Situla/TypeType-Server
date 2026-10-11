package dev.typetype.server.services

object RecommenderUserDataInvalidation {
    @Volatile
    private var invalidator: (suspend (String) -> Unit)? = null

    fun configure(invalidator: suspend (String) -> Unit) {
        this.invalidator = invalidator
    }

    suspend fun invalidate(userId: String) {
        invalidator?.invoke(userId)
    }
}
