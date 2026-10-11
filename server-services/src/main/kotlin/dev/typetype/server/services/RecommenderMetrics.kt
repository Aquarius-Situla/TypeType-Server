package dev.typetype.server.services

import java.util.concurrent.atomic.AtomicLong

class RecommenderMetrics {
    val pagesFromCache = AtomicLong()
    val pagesBuilt = AtomicLong()
    val fallbacks = AtomicLong()
    val buildMillis = AtomicLong()

    val recommenderMillis = AtomicLong()
    val sourceMillis = AtomicLong()
    val sourceRequests = AtomicLong()
    val sourceCacheHits = AtomicLong()
    val streamRequests = AtomicLong()
    val streamRequestsRefused = AtomicLong()
}
