package dev.typetype.server.routes

import dev.typetype.server.RecommenderServices
import dev.typetype.server.models.ErrorResponse
import dev.typetype.server.models.RecommenderSettingsItem
import dev.typetype.server.services.AuthService
import dev.typetype.server.services.RecommenderPageRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

fun Route.recommenderRoutes(
    recommender: RecommenderServices,
    authService: AuthService,
) {
    get("/version/recommender") {
        val version = recommender.client.takeIf { recommender.config.installed }?.version()
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("recommender not installed"))
        call.respond(version)
    }
    get("/recommendations/algorithms") {
        call.withJwtAuth(authService) {
            if (!call.recommenderOffered(recommender)) return@withJwtAuth
            val algorithms = recommender.catalog.list(call.recommenderLocale())
                ?: return@withJwtAuth call.respond(HttpStatusCode.BadGateway, ErrorResponse("recommender unavailable"))
            call.respond(algorithms)
        }
    }
    get("/recommendations/settings") {
        call.withJwtAuth(authService) { userId ->
            if (!call.recommenderOffered(recommender)) return@withJwtAuth
            call.respond(recommender.settingsService.get(userId))
        }
    }
    put("/recommendations/settings") {
        call.withJwtAuth(authService) { userId ->
            if (!call.recommenderOffered(recommender)) return@withJwtAuth
            val settings = runCatching { call.receive<RecommenderSettingsItem>() }.getOrElse {
                return@withJwtAuth call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
            }
            val offered = recommender.catalog.offeredIds(call.recommenderLocale())
            if (settings.algorithmId != null && settings.algorithmId !in offered) {
                return@withJwtAuth call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown 'algorithmId'"))
            }
            val saved = recommender.settingsService.upsert(userId, settings)
            recommender.homeService.forgetPages(userId)
            call.respond(saved)
        }
    }
    get("/recommendations/data") {
        call.withJwtAuth(authService) { userId ->
            if (!call.recommenderOffered(recommender)) return@withJwtAuth
            call.respond(recommender.dataService.overview(userId, recommender.settingsService.get(userId)))
        }
    }
    get("/recommendations/last-home") {
        call.withJwtAuth(authService) { userId ->
            if (!call.recommenderOffered(recommender)) return@withJwtAuth
            val trace = recommender.homeService.lastTrace(userId)
                ?: return@withJwtAuth call.respond(HttpStatusCode.NotFound, ErrorResponse("No page built yet"))
            call.respond(trace)
        }
    }
    get("/recommendations/profile") {
        call.withJwtAuth(authService) { userId ->
            if (!call.recommenderOffered(recommender)) return@withJwtAuth
            val settings = recommender.settingsService.get(userId)
            if (!settings.enabled) {
                return@withJwtAuth call.respond(HttpStatusCode.Forbidden, ErrorResponse("Turn on recommendations to view this profile", "recommender_disabled"))
            }
            val algorithmId = settings.algorithmId ?: recommender.catalog.defaultId(call.recommenderLocale())
                ?: return@withJwtAuth call.respond(HttpStatusCode.BadGateway, ErrorResponse("recommender unavailable"))
            val profile = recommender.profileService.profile(
                RecommenderPageRequest(
                    userId = userId,
                    algorithmId = algorithmId,
                    settings = settings,
                    seed = 0,
                    locale = call.recommenderLocale(),
                    timezone = call.recommenderTimezone(),
                    timeoutMs = recommender.requestTimeoutMs(),
                ),
            ) ?: return@withJwtAuth call.respond(HttpStatusCode.BadGateway, ErrorResponse("recommender unavailable"))
            call.respond(recommender.profileIllustrator.illustrate(userId, profile))
        }
    }
}

internal suspend fun ApplicationCall.recommenderOffered(recommender: RecommenderServices): Boolean {
    val offered = recommender.offered()
    if (!offered) respond(HttpStatusCode.NotFound, ErrorResponse("recommender not available"))
    return offered
}
