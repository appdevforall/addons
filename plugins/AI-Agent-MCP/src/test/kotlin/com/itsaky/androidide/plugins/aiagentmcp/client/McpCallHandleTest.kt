package com.itsaky.androidide.plugins.aiagentmcp.client

import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handshake between a request and a cancel, either way round: a request that publishes its
 * connection after the cancel sends nothing, and a cancel after the request published drops that
 * connection and no other.
 */
class McpCallHandleTest {

    /** A connection that never connects; counts its disconnects, or fails them with [failure]. */
    private class FakeConnection(private val failure: Throwable? = null) :
        HttpURLConnection(URL("http://127.0.0.1/mcp")) {
        var disconnects = 0

        override fun disconnect() {
            failure?.let { throw it }
            disconnects++
        }

        override fun usingProxy() = false

        override fun connect() = Unit
    }

    @Test
    fun `a request attached after the cancel is refused before it sends anything`() {
        val handle = McpCallHandle()
        handle.cancel()

        assertThrows(InterruptedIOException::class.java) { handle.attach(FakeConnection()) }
    }

    @Test
    fun `a cancel after the request attached drops its connection`() {
        val handle = McpCallHandle()
        val conn = FakeConnection()
        handle.attach(conn)

        handle.cancel()

        assertEquals(1, conn.disconnects)
        assertTrue(handle.isCancelled)
    }

    @Test
    fun `a cancel after the request attached is caught before it sends anything`() {
        val handle = McpCallHandle()
        handle.attach(FakeConnection())

        handle.cancel()

        assertThrows(InterruptedIOException::class.java) { handle.throwIfCancelled() }
    }

    @Test
    fun `a request not cancelled goes ahead`() {
        val handle = McpCallHandle()
        handle.attach(FakeConnection())

        handle.throwIfCancelled()

        assertFalse(handle.isCancelled)
    }

    @Test
    fun `a cancel after the request ended drops nothing`() {
        val handle = McpCallHandle()
        val conn = FakeConnection()
        handle.attach(conn)
        handle.detach()

        handle.cancel()

        assertEquals("a finished request's connection may be back in the pool", 0, conn.disconnects)
    }

    @Test
    fun `a disconnect that fails with an Exception is survived`() {
        val handle = McpCallHandle()
        handle.attach(FakeConnection(IllegalStateException("already closed")))

        handle.cancel()

        assertTrue(handle.isCancelled)
    }

    @Test
    fun `an OutOfMemoryError from the disconnect reaches the caller`() {
        val handle = McpCallHandle()
        handle.attach(FakeConnection(OutOfMemoryError("disconnecting")))

        assertThrows(OutOfMemoryError::class.java) { handle.cancel() }
    }
}
