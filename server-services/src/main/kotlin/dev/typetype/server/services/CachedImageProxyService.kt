package dev.typetype.server.services

import dev.typetype.server.cache.CacheService
import dev.typetype.server.models.ExtractionResult
import dev.typetype.server.models.ProxyResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.net.URI
import java.util.Base64

private const val IMAGE_TTL_SECONDS = 24 * 60 * 60L
private const val MAX_IMAGE_BYTES = 512 * 1024
private const val KEPT_CACHE_CONTROL = "public, max-age=$IMAGE_TTL_SECONDS"
// A provider marks the placeholder it answers for a missing thumbnail as good for seconds only.
private const val SHORT_LIVED_SECONDS = 100
private val MAX_AGE = Regex("""max-age=(\d+)""")
private val IMAGE_HOSTS = listOf("ytimg.com", "ggpht.com", "googleusercontent.com")

@Serializable
private data class KeptImage(val contentType: String, val body: String)

class CachedImageProxyService<T>(
    private val delegate: T,
    private val cache: CacheService,
) : ProxyService, ProviderMediaAwareProxyService by delegate
    where T : ProxyService, T : ProviderMediaAwareProxyService {

    override suspend fun pipe(url: String, rangeHeader: String?, domandBid: String?): ExtractionResult<ProxyResponse> {
        if (rangeHeader != null || !isImageHost(url)) return delegate.pipe(url, rangeHeader, domandBid)
        val key = PublicCacheKey.of("proxy-image", url)
        kept(key)?.let { return ExtractionResult.Success(it) }
        val result = delegate.pipe(url, rangeHeader, domandBid)
        if (result !is ExtractionResult.Success || !keepable(result.data)) return result
        return ExtractionResult.Success(keep(key, result.data))
    }

    private suspend fun kept(key: String): ProxyResponse? = runCatching {
        val image = cache.get(key)?.let { Json.decodeFromString(KeptImage.serializer(), it) } ?: return null
        answer(image.contentType, Base64.getDecoder().decode(image.body))
    }.getOrNull()

    private fun keepable(response: ProxyResponse): Boolean {
        val maxAge = response.cacheControl?.let { MAX_AGE.find(it) }?.groupValues?.get(1)?.toLongOrNull() ?: return false
        return response.status == 200 && response.contentType.startsWith("image/") && maxAge >= SHORT_LIVED_SECONDS
    }

    private suspend fun keep(key: String, response: ProxyResponse): ProxyResponse = withContext(Dispatchers.IO) {
        val head = runCatching { response.stream.readNBytes(MAX_IMAGE_BYTES + 1) }
            .getOrElse { response.close(); throw it }
        if (head.size > MAX_IMAGE_BYTES) {
            return@withContext ProxyResponse(
                status = response.status,
                contentType = response.contentType,
                contentLength = response.contentLength,
                contentRange = null,
                acceptRanges = null,
                cacheControl = response.cacheControl,
                stream = SequenceInputStream(ByteArrayInputStream(head), response.stream),
                close = response.close,
            )
        }
        response.close()
        val image = KeptImage(response.contentType, Base64.getEncoder().encodeToString(head))
        runCatching { cache.set(key, Json.encodeToString(KeptImage.serializer(), image), IMAGE_TTL_SECONDS) }
        answer(response.contentType, head)
    }

    private fun answer(contentType: String, body: ByteArray) = ProxyResponse(
        status = 200,
        contentType = contentType,
        contentLength = body.size.toLong(),
        contentRange = null,
        acceptRanges = null,
        cacheControl = KEPT_CACHE_CONTROL,
        stream = ByteArrayInputStream(body),
        close = {},
    )

    private fun isImageHost(url: String): Boolean {
        val host = runCatching { URI(url.substringBefore('#')).host.orEmpty() }.getOrDefault("")
        return IMAGE_HOSTS.any { host == it || host.endsWith(".$it") }
    }
}
