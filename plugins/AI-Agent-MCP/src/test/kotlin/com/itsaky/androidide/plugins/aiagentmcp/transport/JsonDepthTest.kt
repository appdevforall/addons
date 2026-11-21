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
        assertFalse(JsonDepth.exceeds("""{"a":"$brackets","b":"\"$brackets\\","c":["$brackets"]}"""))
    }

    @Test
    fun `the characters refused outside a string are read as text inside one`() {
        val brackets = "[".repeat(500)
        val text = """{"a":"it's; a=b // c /* d */ # e","b":["'$brackets;=/#"],"c" : [ "x" , "y" ]}"""
        assertFalse(JsonDepth.exceeds(text))
    }

    @Test
    fun `a single-quoted string is refused`() {
        // Not JSON; Android's org.json reads it, and a quote inside it hides nothing from that parser.
        assertTrue(JsonDepth.exceeds("['a']"))
    }

    @Test
    fun `a semicolon separator hiding a closing bracket is refused`() {
        // Android's org.json reads ';' as a separator and "]" as a string, so each unit opens one
        // more array; a scan that took the quote for text counted each "]" as a close.
        assertTrue(JsonDepth.exceeds("[;\"]\";".repeat(5_000)))
    }

    @Test
    fun `a comment hiding a closing bracket is refused`() {
        // Android's org.json skips "//]" to the end of the line, so each unit opens one more array.
        assertTrue(JsonDepth.exceeds("[//]\n".repeat(5_000)))
    }

    @Test
    fun `a quote inside unquoted text is refused`() {
        // Android's org.json reads `a"` as one unquoted string, so the brackets after the comma
        // are arrays nested in it, not text inside a string the quote opened.
        assertTrue(JsonDepth.exceeds("[a\",${"[".repeat(5_000)}"))
        assertTrue(JsonDepth.exceeds("""{"x":1",${"[".repeat(5_000)}"""))
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
