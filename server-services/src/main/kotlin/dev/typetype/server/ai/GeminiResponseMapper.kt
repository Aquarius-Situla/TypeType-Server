package dev.typetype.server.ai

import org.slf4j.LoggerFactory

internal object GeminiResponseMapper {
    private const val HTTP_OK = 200
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_SERVER_ERROR_START = 500
    private const val HTTP_SERVER_ERROR_END = 599
    private const val EMPTY_RESPONSE_REASON = "empty_response"
    private const val NOT_FOUND_REASON_PREFIX = "http_404"
    private val logger = LoggerFactory.getLogger(GeminiResponseMapper::class.java)

    fun map(
        model: String,
        statusCode: Int,
        responseBody: String,
        apiKey: String,
        durationMs: Long,
    ): AiCompletionResult = when (statusCode) {
        HTTP_OK -> {
            val text = GeminiCodec.parseSuccessText(responseBody)
            if (text != null) {
                AiCompletionResult.Success(
                    text = text,
                    model = model,
                    providerId = GeminiProvider.PROVIDER_ID,
                    cached = false,
                )
            } else {
                logger.warn("gemini_request event=empty_response model={} status=200 durationMs={}", model, durationMs)
                AiCompletionResult.Failed(EMPTY_RESPONSE_REASON, retryable = false)
            }
        }
        HTTP_TOO_MANY_REQUESTS -> {
            logger.warn("gemini_request event=rate_limited model={} status=429 durationMs={}", model, durationMs)
            AiCompletionResult.RateLimited("upstream_rate_limited")
        }
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> {
            logger.warn(
                "gemini_request event=auth_error model={} status={} durationMs={}",
                model,
                statusCode,
                durationMs,
            )
            AiCompletionResult.Failed("upstream_auth_error", retryable = false)
        }
        HTTP_BAD_REQUEST -> {
            val reason = GeminiCodec.extractErrorReason(statusCode, responseBody, apiKey)
            logger.warn(
                "gemini_request event=bad_request model={} status={} durationMs={} reason={}",
                model,
                statusCode,
                durationMs,
                reason,
            )
            AiCompletionResult.Failed(reason, retryable = false)
        }
        HTTP_NOT_FOUND -> {
            val reason = GeminiCodec.extractErrorReason(statusCode, responseBody, apiKey)
            logger.warn(
                "gemini_request event=model_not_found model={} status={} durationMs={} reason={}",
                model,
                statusCode,
                durationMs,
                reason,
            )
            AiCompletionResult.Failed(reason, retryable = false)
        }
        in HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END -> {
            val reason = GeminiCodec.extractErrorReason(statusCode, responseBody, apiKey)
            logger.warn(
                "gemini_request event=server_error model={} status={} durationMs={} reason={}",
                model,
                statusCode,
                durationMs,
                reason,
            )
            AiCompletionResult.Failed(reason, retryable = true)
        }
        else -> {
            val reason = GeminiCodec.extractErrorReason(statusCode, responseBody, apiKey)
            logger.warn(
                "gemini_request event=unexpected_status model={} status={} durationMs={} reason={}",
                model,
                statusCode,
                durationMs,
                reason,
            )
            AiCompletionResult.Failed(reason, retryable = false)
        }
    }

    fun shouldFallback(result: AiCompletionResult): Boolean = when (result) {
        is AiCompletionResult.RateLimited -> true
        is AiCompletionResult.Failed ->
            result.retryable ||
                result.reason == EMPTY_RESPONSE_REASON ||
                result.reason.startsWith(NOT_FOUND_REASON_PREFIX)
        AiCompletionResult.Disabled, is AiCompletionResult.Success -> false
    }

    fun describeFailure(result: AiCompletionResult): String = when (result) {
        is AiCompletionResult.RateLimited -> "rate_limited:${result.reason}"
        is AiCompletionResult.Failed -> "failed:${result.reason}"
        AiCompletionResult.Disabled -> "disabled"
        is AiCompletionResult.Success -> "success"
    }
}
