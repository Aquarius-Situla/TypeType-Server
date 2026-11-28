package dev.typetype.server.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class GeminiProviderFallbackTest {
    @Test
    fun `falls back sequentially on 503 and 429 and returns hitting model`() = runTest {
        val paths = CopyOnWriteArrayList<String>()
        val server = startServer { exchange ->
            paths.add(exchange.requestURI.path)
            when {
                exchange.requestURI.path.contains("gemini-4-hydrogen") ->
                    respond(exchange, 503, """{"error":{"message":"overloaded"}}""")
                exchange.requestURI.path.contains("gemini-3.8-flash") ->
                    respond(exchange, 429, """{"error":{"message":"quota exhausted"}}""")
                else ->
                    respond(exchange, 200, """{"candidates":[{"content":{"parts":[{"text":"{\"score\":92}"}]}}]}""")
            }
        }
        try {
            val provider = GeminiProvider(
                config = GeminiConfig(
                    apiKey = "AQ.test-key",
                    model = "gemini-4-hydrogen, gemini-3.8-flash, gemini-3.5-flash-lite",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    thinkingLevel = "medium",
                ),
                timeoutMs = 5_000L,
            )
            assertEquals("gemini-4-hydrogen", provider.model)
            val result = provider.complete(AiCompletionRequest("classify"))
            assertEquals(
                AiCompletionResult.Success("""{"score":92}""", "gemini-3.5-flash-lite", "gemini", cached = false),
                result,
            )
            assertEquals(
                listOf(
                    "/v1beta/models/gemini-4-hydrogen:generateContent",
                    "/v1beta/models/gemini-3.8-flash:generateContent",
                    "/v1beta/models/gemini-3.5-flash-lite:generateContent",
                ),
                paths.toList(),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `falls back on 404 and empty 200 and strips thinking config for legacy model`() = runTest {
        val recordedThinking = CopyOnWriteArrayList<String?>()
        val server = startServer { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val thinking = Json.parseToJsonElement(body).jsonObject["generationConfig"]
                ?.jsonObject?.get("thinkingConfig")?.jsonObject?.get("thinkingLevel")?.jsonPrimitive?.content
            recordedThinking.add(thinking)
            when {
                exchange.requestURI.path.contains("gemini-4-hydrogen") ->
                    respond(exchange, 404, """{"error":{"message":"not found"}}""")
                exchange.requestURI.path.contains("gemini-3.8-flash") ->
                    respond(exchange, 200, """{"candidates":[]}""")
                else ->
                    respond(exchange, 200, """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""")
            }
        }
        try {
            val provider = GeminiProvider(
                config = GeminiConfig(
                    apiKey = "AQ.test-key",
                    model = "gemini-4-hydrogen",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    thinkingLevel = "medium",
                    fallbackModels = listOf("gemini-3.8-flash", "gemini-2.5-flash-lite"),
                ),
                timeoutMs = 5_000L,
            )
            val result = provider.complete(AiCompletionRequest("hello"))
            assertEquals(
                AiCompletionResult.Success("ok", "gemini-2.5-flash-lite", "gemini", cached = false),
                result,
            )
            assertEquals(3, recordedThinking.size)
            assertEquals("medium", recordedThinking[0])
            assertEquals("medium", recordedThinking[1])
            assertNull(recordedThinking[2])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `fails fast without fallback on 403 auth error or 400 bad request`() = runTest {
        val paths = CopyOnWriteArrayList<String>()
        var nextStatus = 403
        var nextBody = """{"error":{"message":"forbidden"}}"""
        val server = startServer { exchange ->
            paths.add(exchange.requestURI.path)
            respond(exchange, nextStatus, nextBody)
        }
        try {
            val provider = GeminiProvider(
                config = GeminiConfig(
                    apiKey = "AQ.test-key",
                    model = "gemini-4-hydrogen,gemini-3.5-flash-lite",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                ),
                timeoutMs = 5_000L,
            )
            assertEquals(
                AiCompletionResult.Failed("upstream_auth_error", retryable = false),
                provider.complete(AiCompletionRequest("hello")),
            )
            assertEquals(1, paths.size)

            nextStatus = 400
            nextBody = """{"error":{"message":"invalid schema"}}"""
            val badReq = provider.complete(AiCompletionRequest("hello")) as AiCompletionResult.Failed
            assertFalse(badReq.retryable)
            assertEquals(2, paths.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `falls back after primary socket timeout and returns last failure when all fail`() = runTest {
        val pool = Executors.newCachedThreadPool()
        val paths = CopyOnWriteArrayList<String>()
        var failAll = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/") { exchange ->
                paths.add(exchange.requestURI.path)
                if (failAll) {
                    val status = if (exchange.requestURI.path.contains("gemini-4-hydrogen")) 503 else 429
                    respond(exchange, status, """{"error":{"message":"down"}}""")
                } else if (exchange.requestURI.path.contains("gemini-4-hydrogen")) {
                    Thread.sleep(900L)
                    runCatching { respond(exchange, 200, """{"candidates":[]}""") }
                } else {
                    respond(exchange, 200, """{"candidates":[{"content":{"parts":[{"text":"fallback-ok"}]}}]}""")
                }
            }
            start()
        }
        try {
            val provider = GeminiProvider(
                config = GeminiConfig(
                    apiKey = "AQ.test-key",
                    model = "gemini-4-hydrogen,gemini-3.5-flash-lite",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                ),
                timeoutMs = 350L,
            )
            val recovered = provider.complete(AiCompletionRequest("hello"))
            assertEquals(
                AiCompletionResult.Success("fallback-ok", "gemini-3.5-flash-lite", "gemini", cached = false),
                recovered,
            )
            assertEquals(
                listOf(
                    "/v1beta/models/gemini-4-hydrogen:generateContent",
                    "/v1beta/models/gemini-3.5-flash-lite:generateContent",
                ),
                paths.toList(),
            )

            paths.clear()
            failAll = true
            assertEquals(
                AiCompletionResult.RateLimited("upstream_rate_limited"),
                provider.complete(AiCompletionRequest("hello")),
            )
            assertEquals(2, paths.size)
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    private fun startServer(handler: (HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        return server
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
