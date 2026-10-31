package dev.typetype.server.services

import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.Objects

class BilibiliSubchunkInputStream(
    private val execute: (Request) -> Response,
    private val baseRequest: Request,
    private val subRanges: List<ByteRange>,
    firstResponse: Response,
    private val attemptsPerChunk: Int = 3,
    private val checkActive: () -> Unit = {},
) : InputStream() {
    init {
        require(subRanges.isNotEmpty()) { "subRanges must not be empty" }
    }

    private var currentIndex = 0
    private var currentStream: InputStream? = RetryingProxyInputStream(
        execute = execute,
        request = baseRequest.newBuilder()
            .header("Range", "bytes=${subRanges[0].start}-${subRanges[0].end}")
            .build(),
        initialResponse = firstResponse,
        attemptsRemaining = (attemptsPerChunk - 1).coerceAtLeast(0),
        checkActive = checkActive,
    )
    private var closed = false
    private val singleByte = ByteArray(1)

    override fun read(): Int = if (read(singleByte, 0, 1) == -1) -1 else singleByte[0].toInt() and 0xff

    override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
        Objects.checkFromIndexSize(offset, count, bytes.size)
        if (closed) throw IOException("Stream closed")
        if (count == 0) return 0

        while (true) {
            checkActive()
            val stream = currentStream ?: return -1
            val readBytes = stream.read(bytes, offset, count)
            if (readBytes > 0) {
                return readBytes
            }
            // Current subchunk completed (EOF), close it and advance
            stream.close()
            currentStream = null
            currentIndex++
            if (currentIndex >= subRanges.size) {
                return -1
            }
            currentStream = openSubchunk(currentIndex)
        }
    }

    private fun openSubchunk(index: Int): InputStream {
        val range = subRanges[index]
        val subRequest = baseRequest.newBuilder()
            .header("Range", "bytes=${range.start}-${range.end}")
            .build()
        var lastError: IOException? = null
        for (attempt in 1..attemptsPerChunk) {
            checkActive()
            if (Thread.currentThread().isInterrupted) {
                throw lastError ?: IOException("Interrupted while opening subchunk")
            }
            val response = try {
                execute(subRequest)
            } catch (error: IOException) {
                lastError = error
                continue
            }
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                lastError = IOException("Upstream returned $code for subchunk bytes=${range.start}-${range.end}")
                continue
            }
            return RetryingProxyInputStream(
                execute = execute,
                request = subRequest,
                initialResponse = response,
                attemptsRemaining = attemptsPerChunk - attempt,
                checkActive = checkActive,
            )
        }
        throw lastError ?: IOException("Failed to open subchunk after $attemptsPerChunk attempts")
    }

    override fun close() {
        if (!closed) {
            closed = true
            currentStream?.close()
            currentStream = null
        }
    }
}
