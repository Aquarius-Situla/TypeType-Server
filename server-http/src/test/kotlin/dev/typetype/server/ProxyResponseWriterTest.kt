package dev.typetype.server

import dev.typetype.server.routes.copyProxyStream
import io.ktor.utils.io.ClosedByteChannelException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream

class ProxyResponseWriterTest {
    @Test
    fun `closed downstream channel stops proxy copy`() {
        val output = object : OutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                throw ClosedByteChannelException()
            }

            override fun write(value: Int) = error("single-byte write is not expected")
        }

        assertDoesNotThrow {
            runBlocking { copyProxyStream(ByteArrayInputStream(byteArrayOf(1)), output) }
        }
    }

    @Test
    fun `unrelated output errors propagate`() {
        val failure = IOException("upstream write failed")
        val output = object : OutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                throw failure
            }

            override fun write(value: Int) = error("single-byte write is not expected")
        }

        val thrown = assertThrows(IOException::class.java) {
            runBlocking { copyProxyStream(ByteArrayInputStream(byteArrayOf(1)), output) }
        }
        assertEquals(failure.message, thrown.message)
    }
}
