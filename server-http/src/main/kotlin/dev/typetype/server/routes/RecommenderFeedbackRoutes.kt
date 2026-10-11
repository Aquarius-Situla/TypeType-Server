package dev.typetype.server.routes

import dev.typetype.server.RecommenderServices
import dev.typetype.server.models.ErrorResponse
import dev.typetype.server.models.RecommenderFeedbackItem
import dev.typetype.server.services.AuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.recommenderFeedbackRoutes(
    recommender: RecommenderServices,
    authService: AuthService,
) {
    get("/recommendations/feedback") {
        call.withJwtAuth(authService) { userId ->
            if (!call.recommenderOffered(recommender)) return@withJwtAuth
            call.respond(recommender.feedbackService.list(userId))
        }
    }
    post("/recommendations/feedback") {
        call.withJwtAuth(authService) { userId ->
            if (!call.recommenderOffered(recommender)) return@withJwtAuth
            val request = runCatching { call.receive<RecommenderFeedbackItem>() }.getOrElse {
                return@withJwtAuth call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
            }
            val saved = recommender.feedbackService.add(userId, request)
                ?: return@withJwtAuth call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid 'kind' or 'verdict'"))
            saved.videoUrl?.let { recommender.homeService.hideVideo(userId, it) }
            call.respond(HttpStatusCode.Created, saved)
        }
    }
    delete("/recommendations/feedback/{id}") {
        call.withJwtAuth(authService) { userId ->
            if (!call.recommenderOffered(recommender)) return@withJwtAuth
            val id = call.parameters["id"].orEmpty()
            val removed = recommender.feedbackService.delete(userId, id)
                ?: return@withJwtAuth call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
            removed.videoUrl?.let { recommender.homeService.hideVideo(userId, it, hide = false) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
