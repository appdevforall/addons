package com.itsaky.androidide.plugins.aiagentmcp.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan that keeps a server's deeply nested JSON from any parser. It may count too many
 * brackets, which only refuses a body a parser could have read, never too few.
 */
class JsonDepthTest {

    @Test
    fun `nesting at the limit passes and one level more does not`() {
        val depth = JsonDepth.MAX
        assertFalse(JsonDepth.exceeds("[".repeat(depth) + "]".repeat(depth)))
        assertTrue(JsonDepth.exceeds("[".repeat(depth + 1) + "]".repeat(depth + 1)))
    }

    @Test
    fun `brackets inside strings do not count`() {
        val brackets = "[".repeat(500)
        // An escaped quote at the start of "b" and an escaped backslash at its end: neither ends it.
        assertFalse(JsonDepth.exceeds("""{"a":"$brackets","b":"\"$brackets\\","c":['$brackets']}"""))
    }

    @Test
    fun `the brackets after a double quote inside a single-quoted string count`() {
        // org.json reads '"' as a one-character string, then recurses into the brackets after it.
        assertTrue(JsonDepth.exceeds("['\"',${"[".repeat(200)}", 100))
    }

    @Test
    fun `the brackets after a quote where no value starts count`() {
        // Android's org.json skips the comment, so no string starts at its quote.
        assertTrue(JsonDepth.exceeds("[/*\"*/${"[".repeat(200)}", 100))
    }
}
