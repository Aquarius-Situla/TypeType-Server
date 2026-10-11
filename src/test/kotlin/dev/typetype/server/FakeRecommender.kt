package dev.typetype.server

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FakeRecommender : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val answers = ConcurrentHashMap<String, Pair<Int, String>>()
    private val bodies = ConcurrentHashMap<String, String>()
    private val calls = ConcurrentHashMap<String, AtomicInteger>()
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            bodies[path] = exchange.requestBody.readAllBytes().decodeToString()
            calls.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            val (status, body) = answers[path] ?: (404 to "{}")
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun answer(path: String, body: String, status: Int = 200) {
        answers[path] = status to body
    }

    fun calls(path: String): Int = calls[path]?.get() ?: 0

    fun lastBody(path: String): String = bodies[path].orEmpty()

    override fun close() {
        server.stop(0)
    }
}
