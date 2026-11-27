package org.appdevforall.sqliteexplorer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class ProblemsTest {
    @Test
    fun `connection problems offer to reconnect, SQL errors keep the server's message`() {
        assertTrue(Problems.describe(UnreachableException("x")).reconnect)
        assertTrue(Problems.describe(TokenRejectedException()).reconnect)
        assertTrue(Problems.describe(SocketTimeoutException()).reconnect)

        val sql = Problems.describe(ToolException("no such table: x", null))
        assertEquals("no such table: x", sql.message)
        assertFalse(sql.reconnect)

        val old = Problems.describe(UnsupportedServerException("no column types"))
        assertTrue(old.message.contains("newer Code on the Go"))
        assertFalse(old.reconnect)

        assertFalse(Problems.describe(SecurityException()).reconnect)
    }
}
