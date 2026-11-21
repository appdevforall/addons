package com.itsaky.androidide.plugins.aiagentmcp.errors

import com.itsaky.androidide.plugins.aiagentmcp.transport.JsonDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The log view's display shaping: badges read at a glance, bodies read at all. */
class McpLogFormatTest {

    @Test
    fun givenAnUnauthorizedStatusLine_whenToned_thenItReadsAsAnError() {
        assertEquals(McpLogFormat.Tone.ERROR, McpLogFormat.tone("HTTP/1.1 401 Unauthorized"))
    }

    @Test
    fun givenAnOkStatusLine_whenToned_thenItReadsAsSuccess() {
        // The `1.1` in front must not be mistaken for the code.
        assertEquals(McpLogFormat.Tone.SUCCESS, McpLogFormat.tone("HTTP/1.1 200 OK"))
    }

    @Test
    fun givenARedirect_whenToned_thenItIsNeitherSuccessNorError() {
        assertEquals(McpLogFormat.Tone.REDIRECT, McpLogFormat.tone("HTTP/2 307"))
    }

    @Test
    fun givenNoAnswer_whenToned_thenItReadsAsAnError() {
        assertEquals(McpLogFormat.Tone.ERROR, McpLogFormat.tone(null))
    }

    @Test
    fun givenAStatusLine_whenBadged_thenTheProtocolIsDropped() {
        assertEquals("401 Unauthorized", McpLogFormat.badge("HTTP/1.1 401 Unauthorized"))
    }

    @Test
    fun givenAJsonBody_whenPrettied_thenItIsIndentedAndSlashesAreLeftAlone() {
        // Exact whitespace differs between Android's org.json and the JVM one these tests run on.
        val pretty = McpLogFormat.pretty("""{"url":"https://a.test/x","id":1}""")

        assertTrue(pretty.contains("\n  \"url\": \"https://a.test/x\""))
        assertFalse(pretty.contains("\\/"))
    }

    @Test
    fun givenAnSseStream_whenPrettied_thenEachJsonDataLineIsIndented() {
        val pretty = McpLogFormat.pretty("event: message\ndata: {\"id\":1,\"ok\":true}\n\n")

        assertTrue(pretty.startsWith("event: message\ndata:\n{\n"))
        assertTrue(pretty.contains("\n  \"id\": 1"))
    }

    @Test
    fun givenACappedBody_whenPrettied_thenItIsLeftAsKept() {
        // Cut mid-document, it is not JSON any more; showing it as kept beats showing nothing.
        val capped = """{"tools":[{"name":"a"""

        assertEquals(capped, McpLogFormat.pretty(capped))
    }

    @Test
    fun givenAnExceptionWhileIndenting_whenFormatted_thenTheBodyIsNotJson() {
        assertNull(McpLogFormat.json("{\"a\":1}") { throw IllegalStateException("bad") })
    }

    @Test
    fun givenAnOutOfMemoryErrorWhileIndenting_whenFormatted_thenItPropagates() {
        assertThrows(OutOfMemoryError::class.java) {
            McpLogFormat.json("{\"a\":1}") { throw OutOfMemoryError("indenting") }
        }
    }

    @Test
    fun givenTenThousandNestedBrackets_whenFormatted_thenItIsNotJsonAndNothingThrows() {
        assertNull(McpLogFormat.json("[".repeat(10_000)))
    }

    /**
     * Android's org.json recurses once per level with no limit of its own and catches nothing, so
     * such a body overflows the stack there, and the StackOverflowError propagates. The org.json
     * these tests run on turns an overflow while parsing into a JSONException, so the real parser
     * cannot show it here; a parser that recurses the same way, on a thread with a small stack,
     * stands in for Android's.
     */
    @Test
    fun givenTenThousandNestedBrackets_whenFormatted_thenNoParserRecursesThroughThem() {
        val deep = "[".repeat(10_000)
        var result: String? = "never ran"
        var thrown: Throwable? = null
        val parsing = Thread(null, {
            try {
                result = McpLogFormat.json(deep, ::recursiveIndent)
            } catch (t: Throwable) {
                thrown = t
            }
        }, "small-stack", SMALL_STACK_BYTES)
        parsing.start()
        parsing.join(10_000)

        assertNull("formatting the body threw $thrown", thrown)
        assertNull(result)
    }

    @Test
    fun givenNestingAtTheLimit_whenFormatted_thenItIsIndented() {
        val depth = JsonDepth.MAX
        assertNotNull(McpLogFormat.json("[".repeat(depth) + "]".repeat(depth)))
    }

    @Test
    fun givenNestingOneLevelPastTheLimit_whenFormatted_thenItIsShownAsItCame() {
        val depth = JsonDepth.MAX + 1
        val body = "{\"a\":" + "[".repeat(depth - 1) + "]".repeat(depth - 1) + "}"

        assertNull(McpLogFormat.json(body))
        assertEquals(body, McpLogFormat.pretty(body))
    }

    @Test
    fun givenBracketsInsideStrings_whenFormatted_thenTheyDoNotCount() {
        val brackets = "[".repeat(500)
        // An escaped quote at the start of "b" and an escaped backslash at its end: neither ends it.
        val body = """{"a":"$brackets","b":"\"$brackets\\","c":["$brackets"]}"""

        assertNotNull("brackets inside strings were counted", McpLogFormat.json(body))
    }

    /** Descends once per opening bracket at the start of [text], as a recursive-descent parser does. */
    private fun recursiveIndent(text: String): String? {
        fun descend(index: Int): Int =
            if (index < text.length && (text[index] == '[' || text[index] == '{')) descend(index + 1) + 1 else 0
        descend(0)
        return text
    }

    private companion object {
        /** Far less than 10,000 frames need, whatever the JIT makes of them. */
        const val SMALL_STACK_BYTES = 128L * 1024
    }
}
