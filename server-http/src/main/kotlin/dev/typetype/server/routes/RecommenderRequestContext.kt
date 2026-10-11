package dev.typetype.server.routes

import dev.typetype.server.models.HomeRecommendationsResponse
import io.ktor.server.application.ApplicationCall

private const val MAX_LOCALE_CHARS = 12
private const val MAX_TIMEZONE_CHARS = 64
private const val DEFAULT_LOCALE = "en"
private const val DEFAULT_TIMEZONE = "UTC"

fun ApplicationCall.recommenderLocale(): String =
    (request.queryParameters["locale"] ?: request.headers["Accept-Language"]?.substringBefore(',')?.substringBefore(';'))
        ?.trim()?.take(MAX_LOCALE_CHARS)?.takeIf { it.isNotEmpty() } ?: DEFAULT_LOCALE

fun ApplicationCall.recommenderTimezone(): String =
    request.queryParameters["timezone"]?.trim()?.take(MAX_TIMEZONE_CHARS)?.takeIf { it.isNotEmpty() } ?: DEFAULT_TIMEZONE

fun HomeRecommendationsResponse.withShownRows(): HomeRecommendationsResponse {
    val shown = items.mapTo(HashSet()) { it.url }
    return copy(rows = rows?.map { row -> row.copy(videoUrls = row.videoUrls.filter { it in shown }) }?.filter { it.videoUrls.isNotEmpty() })
}
