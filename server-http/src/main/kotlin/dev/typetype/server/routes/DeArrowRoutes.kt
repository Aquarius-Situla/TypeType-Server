package dev.typetype.server.routes

import dev.typetype.server.models.ErrorResponse
import dev.typetype.server.services.DeArrowUnavailableException
import dev.typetype.server.services.DeArrowService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.deArrowRoutes(service: DeArrowService) {
    get("/dearrow") {
        val videoId = call.request.queryParameters["videoId"].orEmpty()
        val item = try {
            service.get(videoId)
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid videoId"))
        } catch (_: DeArrowUnavailableException) {
            call.response.headers.append(HttpHeaders.RetryAfter, "30")
            return@get call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("DeArrow is temporarily unavailable", "dearrow_unavailable"),
            )
        }
        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
        call.respond(item)
    }
    get("/dearrow/thumbnail") {
        val videoId = call.request.queryParameters["videoId"].orEmpty()
        val timestamp = call.request.queryParameters["time"]?.toDoubleOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid time"))
        val bytes = service.thumbnail(videoId, timestamp)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Thumbnail not found"))
        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=604800")
        call.respondBytes(bytes, deArrowThumbnailContentType(bytes))
    }
}

internal fun deArrowThumbnailContentType(bytes: ByteArray): ContentType = if (
    bytes.size >= 12 &&
    bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
    bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
) {
    ContentType.parse("image/webp")
} else {
    ContentType.Image.JPEG
}
