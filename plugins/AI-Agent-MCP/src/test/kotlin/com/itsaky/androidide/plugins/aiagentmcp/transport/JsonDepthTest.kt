package com.itsaky.androidide.plugins.aiagentmcp.transport

import com.itsaky.androidide.plugins.aiagentmcp.transport.JsonDepth.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scan that keeps a server's deeply nested JSON from any parser. It may count too many
 * brackets, which only refuses a body a parser could have read, never too few. Each refusal names
 * the rule that tripped, too deep or not strict JSON, which is what the server's author is told.
 */
class JsonDepthTest {

    @Test
    fun `nesting at the limit passes and one level more does not`() {
        val depth = JsonDepth.MAX
        assertNull(JsonDepth.refusal("[".repeat(depth) + "]".repeat(depth)))
        assertEquals(Refusal.TooDeep(depth), JsonDepth.refusal("[".repeat(depth + 1) + "]".repeat(depth + 1)))
    }

    @Test
    fun `brackets inside strings do not count`() {
        val brackets = "[".repeat(500)
        // An escaped quote at the start of "b" and an escaped backslash at its end: neither ends it.
        assertNull(JsonDepth.refusal("""{"a":"$brackets","b":"\"$brackets\\","c":["$brackets"]}"""))
    }

    @Test
    fun `the characters refused outside a string are read as text inside one`() {
        val brackets = "[".repeat(500)
        val text = """{"a":"it's; a=b // c /* d */ # e","b":["'$brackets;=/#"],"c" : [ "x" , "y" ]}"""
        assertNull(JsonDepth.refusal(text))
    }

    @Test
    fun `valid strict JSON is accepted`() {
        listOf(
            // A byte order mark, which a JSON parser may ignore and Android's org.json skips.
            "\uFEFF{\"a\":1}",
            """{"n":1e5,"m":-0.5,"p":1E-3,"z":-0,"t":true,"f":false,"x":null}""",
            """{"s":"é","u":"\u00e9","q":"\"\\\/"}""",
            """{"a":[[],[[]],{"b":[]}],"o":{}}""",
            """[{"jsonrpc":"2.0","id":1,"result":{}}]""",
        ).forEach { text -> assertNull("refused: $text", JsonDepth.refusal(text)) }
    }

    @Test
    fun `a single-quoted string is refused`() {
        // Not JSON; Android's org.json reads it, and a quote inside it hides nothing from that parser.
        assertEquals(Refusal.NotStrict('\''), JsonDepth.refusal("['a']"))
    }

    @Test
    fun `a semicolon separator hiding a closing bracket is refused`() {
        // Android's org.json reads ';' as a separator and "]" as a string, so each unit opens one
        // more array; a scan that took the quote for text counted each "]" as a close.
        assertEquals(Refusal.NotStrict(';'), JsonDepth.refusal("[;\"]\";".repeat(5_000)))
    }

    @Test
    fun `a comment hiding a closing bracket is refused`() {
        // Android's org.json skips "//]" to the end of the line, so each unit opens one more array.
        assertEquals(Refusal.NotStrict('/'), JsonDepth.refusal("[//]\n".repeat(5_000)))
    }

    @Test
    fun `a quote inside unquoted text is refused`() {
        // Android's org.json reads `a"` as one unquoted string, so the brackets after the comma
        // are arrays nested in it, not text inside a string the quote opened.
        assertEquals(Refusal.NotStrict('"'), JsonDepth.refusal("[a\",${"[".repeat(5_000)}"))
        assertEquals(Refusal.NotStrict('"'), JsonDepth.refusal("""{"x":1",${"[".repeat(5_000)}"""))
    }

    @Test
    fun `the brackets after a double quote inside a single-quoted string count`() {
        // org.json reads '"' as a one-character string, then recurses into the brackets after it.
        assertEquals(Refusal.NotStrict('\''), JsonDepth.refusal("['\"',${"[".repeat(200)}", 100))
    }

    @Test
    fun `the brackets after a quote where no value starts count`() {
        // Android's org.json skips the comment, so no string starts at its quote.
        assertEquals(Refusal.NotStrict('/'), JsonDepth.refusal("[/*\"*/${"[".repeat(200)}", 100))
    }
}
