package dev.typetype.server.routes

import dev.typetype.server.services.AuthService
import dev.typetype.server.services.RecommenderAdminService
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private const val DEFAULT_LOCALE = "en"
private const val MAX_LOCALE_CHARS = 12

fun Route.adminRecommenderRoutes(
    authService: AuthService,
    recommenderAdminService: RecommenderAdminService,
) {
    get("/admin/recommender/status") {
        call.withAdminAuth(authService) { _ ->
            val locale = call.request.queryParameters["locale"]?.trim()?.take(MAX_LOCALE_CHARS)
                ?.takeIf { it.isNotEmpty() } ?: DEFAULT_LOCALE
            call.respondNoStore(recommenderAdminService.status(locale))
        }
    }
    get("/admin/recommender/feedback") {
        call.withAdminAuth(authService) { _ ->
            call.respondNoStore(recommenderAdminService.feedback())
        }
    }
}
